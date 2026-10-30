package me.youded.fastplace;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * FastPlace premain agent.
 *
 * Sets Minecraft's right-click delay to 0 so a new block can be placed every
 * tick while right-click is held.
 *
 * The target member is located by its field name only, because the client may
 * ship the class under different naming schemes:
 *
 *   MCP : net/minecraft/client/Minecraft   rightClickDelayTimer      rightClickMouse
 *   SRG : (same class name)                field_71467_ac            func_147121_ag
 *   obf : ave                              aq                        ay
 *
 * Everything is matched lazily: classes that do not contain one of the known
 * names are rejected with a cheap byte scan before any ASM parsing happens.
 */
public class Agent {

    private static final Set<String> DELAY_FIELDS =
            Set.of("rightClickDelayTimer", "field_71467_ac", "aq");

    private static final Set<String> RIGHT_CLICK_METHODS =
            Set.of("rightClickMouse", "func_147121_ag", "ay");

    /** Value the client writes when the player clicks (vanilla is 4 ticks). */
    private static final int WRITE_DELAY = 4;

    /** Value written instead: 2 ticks (~100 ms), same as the original fastplace. */
    private static final int NEW_DELAY = 2;

    private static final Path LOG_FILE =
            Paths.get(System.getProperty("java.io.tmpdir"), "fastplace-status.log");

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
        });
    }

    /** Returns the modified class file, or null when the class is not relevant. */
    static byte[] patch(String className, byte[] buffer) {
        if (className == null || buffer == null || buffer.length == 0) {
            return null;
        }
        if (!mightBeTarget(buffer)) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(buffer);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            boolean changed = patchFieldWrites(cn);
            if (!changed) {
                changed = patchRightClickMethod(cn);
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

    /** Cheap pre-filter: the constant pool must mention one of the known members. */
    private static boolean mightBeTarget(byte[] buffer) {
        for (String name : DELAY_FIELDS) {
            if (indexOf(buffer, name) >= 0) {
                return true;
            }
        }
        for (String name : RIGHT_CLICK_METHODS) {
            if (indexOf(buffer, name) >= 0) {
                return true;
            }
        }
        return false;
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

    /** Replaces the constant 4 written into rightClickDelayTimer with 0 (MCP/SRG/obf name). */
    private static boolean patchFieldWrites(ClassNode cn) {
        boolean changed = false;
        for (FieldNode field : cn.fields) {
            if (!"I".equals(field.desc) || !DELAY_FIELDS.contains(field.name)) {
                continue;
            }
            for (MethodNode method : cn.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() != Opcodes.PUTFIELD) {
                        continue;
                    }
                    FieldInsnNode write = (FieldInsnNode) insn;
                    if (!cn.name.equals(write.owner) || !field.name.equals(write.name) || !"I".equals(write.desc)) {
                        continue;
                    }
                    AbstractInsnNode value = previousRealInsn(insn);
                    Integer constant = intConst(value);
                    if (constant == null || constant != WRITE_DELAY) {
                        continue;
                    }
                    method.instructions.set(value, intInsn(NEW_DELAY));
                    log(cn.name + "." + method.name + " -> delay " + NEW_DELAY);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Fallback: known right-click method names, replace their 4 constant with the new delay. */
    private static boolean patchRightClickMethod(ClassNode cn) {
        boolean changed = false;
        for (MethodNode method : cn.methods) {
            if (!RIGHT_CLICK_METHODS.contains(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.ICONST_4) {
                    method.instructions.set(insn, intInsn(NEW_DELAY));
                    log(cn.name + "." + method.name + " (fallback) -> delay " + NEW_DELAY);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static AbstractInsnNode intInsn(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    private static AbstractInsnNode previousRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && prev.getOpcode() < 0) {
            prev = prev.getPrevious();
        }
        return prev;
    }

    private static Integer intConst(AbstractInsnNode insn) {
        if (insn == null) {
            return null;
        }
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return ((IntInsnNode) insn).operand;
        }
        if (opcode == Opcodes.LDC) {
            Object cst = ((LdcInsnNode) insn).cst;
            if (cst instanceof Integer value) {
                return value;
            }
        }
        return null;
    }

    private static void resetLog() {
        try {
            Files.write(LOG_FILE, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignored) {
            // Logging must never break class loading.
        }
    }

    private static void log(String message) {
        System.out.println("[FastPlace] " + message);
        try {
            Files.write(LOG_FILE, (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // Logging must never break class loading.
        }
    }
}
