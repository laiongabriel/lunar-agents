package me.zircta.nohitdelay;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * NoHitDelay premain agent.
 *
 * Removes the 10-tick cooldown applied after a missed left click. In 1.8.9 the
 * only places that assign {@code leftClickCounter = 10} are the two miss
 * branches of Minecraft.clickMouse, so both are set to 0.
 *
 * The approach combines the best parts of the two reference projects:
 *   - WeaveNoHitDelay: only touch clickMouse, never the whole class blindly.
 *   - NoHitDelayDll: pattern-based lookup (bipush 10 + putfield), support for
 *     several mappings, validation of how many assignments were found and
 *     clear status reporting.
 *
 * Unlike the DLL, the patch never throws while the game class is loading: if
 * the pattern is not found the class is left untouched and the reason is
 * written to the status log.
 */
public class Agent {

    private static final String MCP = "net/minecraft/client/Minecraft";
    private static final String LEGACY_FABRIC = "net/minecraft/class_1600";
    private static final String NOTCH = "ave";

    private static final String DELAY_FIELD = "leftClickCounter";
    private static final String CLICK_METHOD = "clickMouse";
    private static final int DELAY_TICKS = 10;

    /**
     * Log message that only exists in clickMouse (null hit-result branch). Used to
     * find the method when mappings rename it, as the hitdelayfix agent does.
     */
    private static final String CLICK_MARKER = "Null returned as 'hitResult', this shouldn't happen!";

    /** Both assignments in 1.8.9 clickMouse must be patched. */
    private static final int EXPECTED_ASSIGNMENTS = 2;

    private static final Path LOG_FILE =
            Path.of(System.getProperty("java.io.tmpdir"), "nohitdelay-status.log");

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

        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(type) || !isMinecraftClass(type)) {
                continue;
            }
            try {
                inst.retransformClasses(type);
                log("retransformed " + type.getName());
            } catch (Throwable t) {
                log("retransform failed " + type.getName() + ": " + t);
            }
        }
    }

    private static boolean isMinecraftClass(Class<?> type) {
        String name = type.getName();
        if (name.equals(MCP) || name.equals(LEGACY_FABRIC) || name.equals(NOTCH)) {
            return true;
        }
        try {
            Field field = type.getDeclaredField(DELAY_FIELD);
            return field.getType() == int.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns the modified class file, or null when the class is not relevant. */
    static byte[] patch(String className, byte[] buffer) {
        if (className == null || buffer == null || buffer.length == 0) {
            return null;
        }
        if (!isCandidate(className)) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(buffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            if (!isMinecraft(cn)) {
                return null;
            }

            int count = removeHitDelay(cn);
            if (count == 0) {
                log("no hit-delay assignment found in " + className);
                return null;
            }
            if (count != EXPECTED_ASSIGNMENTS) {
                log("warning: expected " + EXPECTED_ASSIGNMENTS + " assignments in " + className
                        + " but found " + count);
            }

            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            log("failed on " + className + ": " + t);
            return null;
        }
    }

    private static boolean isCandidate(String className) {
        return className.equals(MCP)
                || className.equals(LEGACY_FABRIC)
                || className.equals(NOTCH)
                || className.endsWith("/Minecraft");
    }

    private static boolean isMinecraft(ClassNode cn) {
        if (cn.name.equals(MCP) || cn.name.equals(LEGACY_FABRIC) || cn.name.equals(NOTCH)) {
            return true;
        }
        return hasIntField(cn, DELAY_FIELD) && hasMethod(cn, CLICK_METHOD, "()V");
    }

    private static boolean hasIntField(ClassNode cn, String name) {
        for (FieldNode field : cn.fields) {
            if (field.name.equals(name) && field.desc.equals("I")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode cn, String name, String desc) {
        return findMethod(cn, name, desc) != null;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode method : cn.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode findByMarker(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (methodContainsString(method, CLICK_MARKER)) {
                return method;
            }
        }
        return null;
    }

    private static boolean methodContainsString(MethodNode method, String value) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.LDC && insn instanceof LdcInsnNode) {
                if (value.equals(((LdcInsnNode) insn).cst)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Patching
    // ------------------------------------------------------------------

    private static int removeHitDelay(ClassNode cn) {
        // Every "bipush 10" that is immediately followed by "putfield" of an int
        // field declared by this class. Grouped by field so we can pick the
        // leftClickCounter one when mappings are available.
        Map<String, List<AbstractInsnNode>> everywhere = new LinkedHashMap<>();
        Map<String, List<AbstractInsnNode>> inClickMouse = new LinkedHashMap<>();

        MethodNode clickByName = findMethod(cn, CLICK_METHOD, "()V");
        MethodNode clickMethod = clickByName != null ? clickByName : findByMarker(cn);
        String clickMethodName = clickByName != null ? CLICK_METHOD : "string marker";

        for (MethodNode method : cn.methods) {
            boolean clickMouse = method == clickMethod;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.BIPUSH || ((IntInsnNode) insn).operand != DELAY_TICKS) {
                    continue;
                }
                AbstractInsnNode next = nextReal(insn);
                if (next == null || next.getOpcode() != Opcodes.PUTFIELD) {
                    continue;
                }
                FieldInsnNode field = (FieldInsnNode) next;
                if (!field.owner.equals(cn.name) || !"I".equals(field.desc) || !hasIntField(cn, field.name)) {
                    continue;
                }
                everywhere.computeIfAbsent(field.name, key -> new ArrayList<>()).add(insn);
                if (clickMouse) {
                    inClickMouse.computeIfAbsent(field.name, key -> new ArrayList<>()).add(insn);
                }
            }
        }

        // 1. Best case (MCP/Lunar): known field, scoped to clickMouse.
        List<AbstractInsnNode> chosen = inClickMouse.get(DELAY_FIELD);
        String reason = "field " + DELAY_FIELD + " in " + clickMethodName;

        // 2. Different mapping: still scoped to clickMouse, like WeaveNoHitDelay,
        //    as long as the method only assigns one int field there.
        if (chosen == null || chosen.isEmpty()) {
            if (inClickMouse.size() == 1) {
                chosen = inClickMouse.values().iterator().next();
                reason = clickMethodName;
            }
        }

        // 3. clickMouse renamed/absent: use the known field name anywhere.
        if (chosen == null || chosen.isEmpty()) {
            chosen = everywhere.get(DELAY_FIELD);
            reason = "field " + DELAY_FIELD;
        }

        // 4. Last resort (fully obfuscated names): only patch when the whole
        //    class has one single int field receiving the constant, like the DLL.
        if (chosen == null || chosen.isEmpty()) {
            if (everywhere.size() == 1) {
                chosen = everywhere.values().iterator().next();
                reason = "single int field";
            }
        }

        if (chosen == null || chosen.isEmpty()) {
            return 0;
        }
        int count = chosen.size();

        // bipush 0 keeps the instruction length, so no stack map frames change.
        for (AbstractInsnNode insn : chosen) {
            ((IntInsnNode) insn).operand = 0;
        }

        log("patched " + count + " assignment(s) via " + reason
                + " in " + cn.name + " (" + join(chosen, cn) + ")");
        return count;
    }

    private static String join(List<AbstractInsnNode> instructions, ClassNode cn) {
        StringBuilder out = new StringBuilder();
        for (AbstractInsnNode insn : instructions) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(ownerOf(cn, insn));
        }
        return out.toString();
    }

    private static String ownerOf(ClassNode cn, AbstractInsnNode instruction) {
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn == instruction) {
                    return method.name + method.desc;
                }
            }
        }
        return "?";
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    private static void resetLog() {
        try {
            Files.writeString(LOG_FILE, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        System.out.println("[NoHitDelay] " + message);
        try {
            Files.writeString(LOG_FILE, message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }
}
