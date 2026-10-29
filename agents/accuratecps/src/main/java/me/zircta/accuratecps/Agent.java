package me.zircta.accuratecps;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * AccurateCPS premain agent.
 *
 * Counts the real click actions (Minecraft.clickMouse / rightClickMouse) instead
 * of the raw mouse-button events, then replaces Lunar's CPS getters so the HUD
 * shows those numbers. This is what makes the right-click show 10 CPS while the
 * FastPlace agent is holding right click (rightClickMouse is called every 2 ticks).
 *
 * Everything the injected code touches lives inside the game classes:
 *   - net.minecraft.client.Minecraft gets the static counters + helper methods
 *   - the Lunar tracker getters are rewritten to call those helper methods
 * That is required because GenesisClassLoader cannot see agent classes.
 */
public class Agent {

    private static final String HELPER = "me/zircta/accuratecps/CpsRuntime";
    private static final String DEFAULT_MINECRAFT = "net/minecraft/client/Minecraft";

    private static final String LONG_LIST_DESC = "Lit/unimi/dsi/fastutil/longs/LongList;";
    private static final String LONG_LIST = "it/unimi/dsi/fastutil/longs/LongList";
    private static final String PREFIX = "fastcps$";

    private static final Set<String> LEFT_CLICK_METHODS =
            Set.of("clickMouse", "func_147116_af", "aw");
    private static final Set<String> RIGHT_CLICK_METHODS =
            Set.of("rightClickMouse", "func_147121_ag", "ay");
    private static final Set<String> TICK_METHODS =
            Set.of("runTick", "func_71407_l");

    private static final Path LOG_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "accuratecps-status.log");

    private static volatile String minecraftName = DEFAULT_MINECRAFT;

    public static void premain(String args, Instrumentation inst) {
        resetLog();
        log("agent starting");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                byte[] patched = patch(className, classfileBuffer);
                if (patched != null) {
                    log("patched " + className);
                }
                return patched;
            }
        }, true);
    }

    /** Used when the jar is attached to an already running client. */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);

        List<Class<?>> minecraft = new ArrayList<>();
        List<Class<?>> trackers = new ArrayList<>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(type)) {
                continue;
            }
            String name = type.getName();
            if (name.equals(DEFAULT_MINECRAFT)) {
                minecraft.add(type);
            } else if (name.startsWith("com.moonsworth.") && hasLongLists(type)) {
                trackers.add(type);
            }
        }
        // Minecraft first: the tracker getters reference it.
        for (Class<?> type : minecraft) {
            retransform(inst, type);
        }
        for (Class<?> type : trackers) {
            retransform(inst, type);
        }
    }

    private static void retransform(Instrumentation inst, Class<?> type) {
        try {
            inst.retransformClasses(type);
            log("retransformed " + type.getName());
        } catch (Throwable t) {
            log("retransform failed " + type.getName() + ": " + t);
        }
    }

    private static boolean hasLongLists(Class<?> type) {
        try {
            int count = 0;
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().equals("it.unimi.dsi.fastutil.longs.LongList")) {
                    count++;
                }
            }
            return count >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns the modified class file, or null when the class is not relevant. */
    static byte[] patch(String className, byte[] buffer) {
        if (className == null || buffer == null || buffer.length == 0) {
            return null;
        }

        boolean lunar = className.startsWith("com/moonsworth/");
        if (lunar) {
            // Cheap pre-filter before parsing thousands of Lunar classes.
            if (indexOf(buffer, "it/unimi/dsi/fastutil/longs/LongList") < 0) {
                return null;
            }
        } else if (!className.equals(DEFAULT_MINECRAFT)
                && !className.endsWith("/Minecraft")
                && !className.equals("Minecraft")
                && !className.equals("ave")) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(buffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            boolean changed;
            if (lunar) {
                changed = patchTracker(cn);
            } else if (isMinecraft(cn)) {
                changed = injectCps(cn);
            } else {
                return null;
            }
            if (!changed) {
                return null;
            }

            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            log("failed on " + className + ": " + t);
            return null;
        }
    }

    private static boolean isMinecraft(ClassNode cn) {
        if (cn.name.equals(DEFAULT_MINECRAFT)) {
            return true;
        }
        return hasMethod(cn, LEFT_CLICK_METHODS, "()V")
                && hasMethod(cn, RIGHT_CLICK_METHODS, "()V")
                && hasMethod(cn, TICK_METHODS, "()V");
    }

    private static boolean hasMethod(ClassNode cn, Set<String> names, String desc) {
        for (MethodNode method : cn.methods) {
            if (names.contains(method.name) && desc.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Minecraft: inject the counters and the click/tick calls
    // ------------------------------------------------------------------

    private static boolean injectCps(ClassNode cn) {
        ClassNode helper = loadHelper(cn.name);
        if (helper == null) {
            return false;
        }
        minecraftName = cn.name;

        Set<String> existingMethods = new HashSet<>();
        for (MethodNode method : cn.methods) {
            existingMethods.add(method.name + method.desc);
        }
        Set<String> existingFields = new HashSet<>();
        for (FieldNode field : cn.fields) {
            existingFields.add(field.name + field.desc);
        }

        for (FieldNode field : helper.fields) {
            if (!existingFields.contains(field.name + field.desc)) {
                cn.fields.add(field);
            }
        }
        for (MethodNode method : helper.methods) {
            if (method.name.startsWith("<")) {
                continue;
            }
            if (!existingMethods.contains(method.name + method.desc)) {
                cn.methods.add(method);
            }
        }

        boolean changed = false;
        for (MethodNode method : cn.methods) {
            if (!"()V".equals(method.desc)) {
                continue;
            }
            String call = null;
            if (LEFT_CLICK_METHODS.contains(method.name)) {
                call = PREFIX + "leftClick";
            } else if (RIGHT_CLICK_METHODS.contains(method.name)) {
                call = PREFIX + "rightClick";
            } else if (TICK_METHODS.contains(method.name)) {
                call = PREFIX + "tick";
            }
            if (call == null || startsWithCall(method, cn.name, call)) {
                continue;
            }
            method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, call, "()V", false));
            log("hooked " + cn.name + "." + method.name);
            changed = true;
        }
        return changed;
    }

    private static boolean startsWithCall(MethodNode method, String owner, String name) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        if (insn instanceof MethodInsnNode call) {
            return call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals("()V");
        }
        return false;
    }

    private static ClassNode loadHelper(String target) {
        try (InputStream in = Agent.class.getResourceAsStream("/" + HELPER + ".class")) {
            if (in == null) {
                log("CpsRuntime.class resource not found");
                return null;
            }
            ClassNode out = new ClassNode();
            ClassReader cr = new ClassReader(in.readAllBytes());
            cr.accept(new ClassRemapper(out, new SimpleRemapper(HELPER, target)), 0);
            return out;
        } catch (Throwable t) {
            log("helper load failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Lunar tracker: replace the two CPS getters
    // ------------------------------------------------------------------

    private static boolean patchTracker(ClassNode cn) {
        List<String> listFields = new ArrayList<>();
        for (FieldNode field : cn.fields) {
            if (LONG_LIST_DESC.equals(field.desc)) {
                listFields.add(field.name);
            }
        }
        if (listFields.size() < 2) {
            return false;
        }

        MethodNode leftGetter = null;
        Set<String> leftFields = new HashSet<>();
        for (MethodNode method : cn.methods) {
            if (!"(Z)I".equals(method.desc) || !callsSize(method)) {
                continue;
            }
            Set<String> read = listFieldsRead(cn, method);
            if (read.isEmpty()) {
                continue;
            }
            leftGetter = method;
            leftFields.addAll(read);
            break;
        }

        String rightField = findRightField(cn);
        MethodNode rightGetter = null;
        for (MethodNode method : cn.methods) {
            if (!"()I".equals(method.desc) || !callsSize(method)) {
                continue;
            }
            Set<String> read = listFieldsRead(cn, method);
            if (read.isEmpty()) {
                continue;
            }
            String field = read.iterator().next();
            boolean matches = rightField != null ? field.equals(rightField) : !leftFields.contains(field);
            if (matches) {
                rightGetter = method;
                break;
            }
        }

        boolean changed = false;
        if (leftGetter != null) {
            replaceGetter(leftGetter, PREFIX + "left");
            log("left getter " + cn.name + "." + leftGetter.name + leftGetter.desc);
            changed = true;
        }
        if (rightGetter != null) {
            replaceGetter(rightGetter, PREFIX + "right");
            log("right getter " + cn.name + "." + rightGetter.name + rightGetter.desc);
            changed = true;
        }
        return changed;
    }

    private static void replaceGetter(MethodNode method, String callee) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, minecraftName, callee, "()I", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
    }

    private static Set<String> listFieldsRead(ClassNode cn, MethodNode method) {
        Set<String> out = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (field.owner.equals(cn.name) && LONG_LIST_DESC.equals(field.desc)) {
                    out.add(field.name);
                }
            }
        }
        return out;
    }

    private static boolean callsSize(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEINTERFACE) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals(LONG_LIST) && call.name.equals("size") && call.desc.equals("()I")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Finds the LongList that receives clicks in the "button == 1" branch of the
     * click recording handler. That list is the right-click one.
     */
    private static String findRightField(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() != Opcodes.ICONST_1) {
                    continue;
                }
                AbstractInsnNode next = nextReal(insn);
                if (next == null || next.getOpcode() != Opcodes.IF_ICMPNE) {
                    continue;
                }
                AbstractInsnNode cursor = next;
                for (int i = 0; i < 25 && cursor != null; i++, cursor = nextReal(cursor)) {
                    if (cursor.getOpcode() != Opcodes.GETFIELD) {
                        continue;
                    }
                    FieldInsnNode field = (FieldInsnNode) cursor;
                    if (!field.owner.equals(cn.name) || !LONG_LIST_DESC.equals(field.desc)) {
                        continue;
                    }
                    AbstractInsnNode add = cursor;
                    for (int j = 0; j < 8 && add != null; j++, add = nextReal(add)) {
                        if (add.getOpcode() == Opcodes.INVOKEINTERFACE) {
                            MethodInsnNode call = (MethodInsnNode) add;
                            if (call.owner.equals(LONG_LIST) && call.name.equals("add")) {
                                return field.name;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void resetLog() {
        try {
            Files.writeString(LOG_FILE, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[AccurateCPS] " + message);
        try {
            Files.writeString(LOG_FILE, message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static int indexOf(byte[] haystack, String needle) {
        int n = needle.length();
        if (n == 0 || haystack.length < n) {
            return -1;
        }
        byte first = (byte) needle.charAt(0);
        int limit = haystack.length - n;
        outer:
        for (int i = 0; i <= limit; i++) {
            if (haystack[i] != first) {
                continue;
            }
            for (int k = 1; k < n; k++) {
                if (haystack[i + k] != (byte) needle.charAt(k)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
