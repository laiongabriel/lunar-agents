package me.zircta.altmanager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Alt manager premain agent.
 *
 * Adds an offline account manager to Lunar Client 1.8.9:
 *
 *   - Minecraft.getSession() returns the account marked active in
 *     alts.properties (falls back to the original session when none is set);
 *   - GuiMultiplayer (server list) gets an "Alt Manager" button, plus F6 as a
 *     shortcut from inside the world;
 *   - the GuiAltManager screen is defined inside the game's own class loader by
 *     the injected code itself, because the agent classes are not visible to
 *     GenesisClassLoader.
 */
public final class Agent {

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GUI_MULTIPLAYER = "net/minecraft/client/gui/GuiMultiplayer";
    private static final String GUI_BUTTON = "net/minecraft/client/gui/GuiButton";
    private static final String GUI_CLASS = "net/minecraft/client/gui/GuiAltManager";
    private static final String RUNTIME = "me/zircta/altmanager/AltRuntime";
    private static final String SESSION = "net/minecraft/util/Session";

    private static final String CONFIG_PROPERTY = "altmanager.config";
    private static final String GUI_PROPERTY = "altmanager.gui";

    private static final String BUTTON_TEXT = "Alt Manager";
    private static final int BUTTON_ID = 1337;

    private static final Path LOG_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "altmanager-status.log");

    private static final Object LOG_LOCK = new Object();

    private static volatile String minecraftName = MINECRAFT;

    public static void premain(String args, Instrumentation inst) {
        resetLog();
        log("agent starting");

        Path config = defaultConfigPath();
        System.setProperty(CONFIG_PROPERTY, config.toString());
        ensureConfig(config);

        publishGuiClass();

        inst.addTransformer(new Transformer(), true);
    }

    /** Used when the jar is attached to an already running client. */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);

        for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName();
            if (!name.equals(minecraftName) && !name.equals(GUI_MULTIPLAYER)) {
                continue;
            }
            if (!inst.isModifiableClass(type)) {
                continue;
            }
            try {
                inst.retransformClasses(type);
                log("retransformed " + name);
            } catch (Throwable t) {
                log("retransform failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * alts.properties lives next to the agent jar, which is normally
     * <lunar>/agents/altmanager.jar.
     */
    private static Path defaultConfigPath() {
        try {
            Path jar = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            if (dir != null) {
                return dir.resolve("alts.properties");
            }
        } catch (Throwable t) {
            log("could not resolve the agent jar location: " + t);
        }

        return Path.of(System.getProperty("java.io.tmpdir"), "alts.properties");
    }

    private static void ensureConfig(Path config) {
        try {
            if (Files.exists(config)) {
                return;
            }

            String defaults = "# Offline accounts used by the alt manager agent.\\n"
                    + "# Accounts are edited in game through the alt manager screen.\\n"
                    + "active=\\n"
                    + "accounts=\\n";

            Files.write(config, defaults.getBytes(StandardCharsets.UTF_8));
            log("created " + config);
        } catch (Throwable t) {
            log("could not create " + config + ": " + t);
        }
    }

    /**
     * The screen class is published as a plain .class file so the injected code
     * can define it inside the game loader. The agent jar itself is not visible
     * to GenesisClassLoader.
     */
    private static void publishGuiClass() {
        try (InputStream in = Agent.class.getResourceAsStream("/" + GUI_CLASS + ".class")) {
            if (in == null) {
                log(GUI_CLASS + ".class not found in the agent jar");
                return;
            }

            Path out = Path.of(System.getProperty("java.io.tmpdir"), "altmanager-gui.class");
            Files.write(out, readFully(in));
            System.setProperty(GUI_PROPERTY, out.toString());
            log("published " + out);
        } catch (Throwable t) {
            log("could not publish the GUI class: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Transformer
    // ------------------------------------------------------------------

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null || classfileBuffer == null || classfileBuffer.length == 0) {
                return null;
            }

            try {
                if (className.equals(MINECRAFT) || className.endsWith("/Minecraft")) {
                    return patchMinecraft(className, classfileBuffer);
                }
                if (className.equals(GUI_MULTIPLAYER)) {
                    return patchMultiplayer(className, classfileBuffer);
                }
            } catch (Throwable t) {
                log("failed on " + className + ": " + t);
            }

            return null;
        }
    }

    private static byte[] patchMinecraft(String className, byte[] classfileBuffer) {
        if (indexOf(classfileBuffer, "runTick") < 0) {
            return null;
        }

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        if (!isMinecraft(cn)) {
            return null;
        }

        minecraftName = cn.name;

        boolean changed = injectRuntime(cn);
        changed |= hookSessionGetter(cn);
          if (!changed) {
            return null;
        }

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        log("patched " + className);
        return cw.toByteArray();
    }

    private static byte[] patchMultiplayer(String className, byte[] classfileBuffer) {
        ClassNode cn = new ClassNode();
        new ClassReader(classfileBuffer).accept(cn, 0);

        boolean changed = false;

        MethodNode init = findVoidMethod(cn, "initGui");
        if (init == null) {
            init = findVoidMethod(cn, "createButtons");
        }
        if (init != null && !containsLdc(init, BUTTON_TEXT)) {
            insertButton(cn, init);
            log("added the alt manager button to " + className + "." + init.name);
            changed = true;
        }

        MethodNode action = findButtonAction(cn);
        if (action != null && !containsCall(action, minecraftName, "alt$open")) {
            insertActionHook(cn, action);
            log("hooked " + className + "." + action.name + action.desc);
            changed = true;
        } else if (action == null) {
            log("no GuiButton handler found in " + className + "; the button will not work");
        }

        if (!changed) {
            return null;
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static MethodNode findVoidMethod(ClassNode cn, String name) {
        for (MethodNode method : cn.methods) {
            if (method.name.equals(name) && method.desc.equals("()V")) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode findButtonAction(ClassNode cn) {
        String desc = "(L" + GUI_BUTTON + ";)V";
        MethodNode fallback = null;

        for (MethodNode method : cn.methods) {
            if (!method.desc.equals(desc)) {
                continue;
            }
            if (method.name.equals("actionPerformed") || method.name.equals("func_73875_a")) {
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }

        return fallback;
    }

    /**
     * this.buttonList.add(new GuiButton(1337, 5, this.height - 30, 120, 20, "Alt Manager"));
     */
    private static void insertButton(ClassNode cn, MethodNode method) {
        AbstractInsnNode lastReturn = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                lastReturn = insn;
            }
        }
        if (lastReturn == null) {
            log("no RETURN in " + method.name + "; button not added");
            return;
        }

        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "buttonList", "Ljava/util/List;"));
        code.add(new TypeInsnNode(Opcodes.NEW, GUI_BUTTON));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode(BUTTON_ID));
        code.add(new InsnNode(Opcodes.ICONST_5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "height", "I"));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 30));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new IntInsnNode(Opcodes.SIPUSH, 120));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 20));
        code.add(new LdcInsnNode(BUTTON_TEXT));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, GUI_BUTTON, "<init>",
                "(IIIIILjava/lang/String;)V", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true));
        code.add(new InsnNode(Opcodes.POP));

        method.instructions.insertBefore(lastReturn, code);
    }

    /**
     * if (button.id == 1337) { Minecraft.alt$open(this); return; }
     */
    private static void insertActionHook(ClassNode cn, MethodNode method) {
        LabelNode skip = new LabelNode();

        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, GUI_BUTTON, "id", "I"));
        code.add(new LdcInsnNode(BUTTON_ID));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, skip));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, minecraftName, "alt$open",
                "(Lnet/minecraft/client/gui/GuiScreen;)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(skip);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        method.instructions.insert(code);
    }

    private static boolean isMinecraft(ClassNode cn) {
        boolean tick = false;
        boolean session = false;
        for (MethodNode method : cn.methods) {
            if (method.name.equals("runTick") && method.desc.equals("()V")) {
                tick = true;
            }
            if (returnsSession(method)) {
                session = true;
            }
        }
        return tick && session;
    }

    private static boolean injectRuntime(ClassNode cn) {
        ClassNode helper = loadHelper(cn.name);
        if (helper == null) {
            return false;
        }

        boolean changed = false;

        for (FieldNode field : helper.fields) {
            if (!hasField(cn, field.name, field.desc)) {
                cn.fields.add(field);
                changed = true;
            }
        }

        for (MethodNode method : helper.methods) {
            if (method.name.startsWith("<") || hasMethod(cn, method.name, method.desc)) {
                continue;
            }
            cn.methods.add(method);
            changed = true;
        }

        return changed;
    }

    /**
     * getSession() returns the active offline account, or runs the original
     * body when no account is configured:
     *
     *   if (!alt$ready()) { <original body> }
     *   return (Session) alt$session();
     *
     * The fallback label needs an explicit F_SAME frame: it is a branch target
     * with the same locals and an empty stack as the method entry.
     */
    private static boolean hookSessionGetter(ClassNode cn) {
        MethodNode target = null;

        for (MethodNode method : cn.methods) {
            if ((method.name.equals("getSession") || method.name.equals("func_110432_I"))
                    && returnsSession(method)) {
                target = method;
                break;
            }
        }

        if (target == null) {
            for (MethodNode method : cn.methods) {
                if (returnsSession(method)) {
                    target = method;
                    break;
                }
            }
        }

        if (target == null || startsWithCall(target, cn.name, "alt$ready")) {
            return false;
        }

        String type = target.desc.substring(3, target.desc.length() - 1);
        LabelNode fallback = new LabelNode();

        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "alt$ready", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "alt$session", "()Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, type));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(fallback);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        target.instructions.insert(code);
        log("hooked " + cn.name + "." + target.name + target.desc);
        return true;
    }

    private static boolean returnsSession(MethodNode method) {
        if (!method.desc.startsWith("()L")) {
            return false;
        }
        return isSessionClass(method.desc.substring(3, method.desc.length() - 1));
    }

    private static boolean isSessionClass(String internalName) {
        return internalName.equals(SESSION) || internalName.endsWith("/Session") || internalName.equals("Session");
    }

    private static boolean startsWithCall(MethodNode method, String owner, String name) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        return insn instanceof MethodInsnNode
                && ((MethodInsnNode) insn).owner.equals(owner)
                && ((MethodInsnNode) insn).name.equals(name);
    }

    private static boolean containsCall(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals(owner) && call.name.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsLdc(MethodNode method, String value) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode && value.equals(((LdcInsnNode) insn).cst)) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode loadHelper(String target) {
        try (InputStream in = Agent.class.getResourceAsStream("/" + RUNTIME + ".class")) {
            if (in == null) {
                log("AltRuntime.class not found in the agent jar");
                return null;
            }

            ClassNode out = new ClassNode();
            new ClassReader(readFully(in)).accept(
                    new ClassRemapper(out, new SimpleRemapper(RUNTIME, target)), 0);
            return out;
        } catch (Throwable t) {
            log("helper load failed: " + t);
            return null;
        }
    }

    private static byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean hasField(ClassNode cn, String name, String desc) {
        for (FieldNode field : cn.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode cn, String name, String desc) {
        for (MethodNode method : cn.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    static void resetLog() {
        synchronized (LOG_LOCK) {
            try {
                Files.write(LOG_FILE, new byte[0],
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Throwable ignored) {
                // Logging must never break class loading.
            }
        }
    }

    static void log(String message) {
        synchronized (LOG_LOCK) {
            try {
                Files.write(LOG_FILE, (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
            } catch (Throwable ignored) {
                // Logging must never break class loading.
            }
        }
    }
}


