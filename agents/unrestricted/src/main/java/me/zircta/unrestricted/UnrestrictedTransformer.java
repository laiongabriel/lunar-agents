package me.zircta.unrestricted;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Port of the five independent matchers from weave-unrestricted.
 *
 * The original transformer used Weave's Instrumentation hook and
 * {@code COMPUTE_FRAMES}. Here it runs through the standard agent premain and
 * uses {@code COMPUTE_MAXS}: none of the patches introduces new stack map
 * frames, and frame computation would force the agent classloader to resolve
 * Minecraft/Lunar classes.
 */
public final class UnrestrictedTransformer implements ClassFileTransformer {

    private static final String BLACKLIST_MARKER = "modSettings";
    private static final String POLLING_MARKER =
            "Unable to start polling detection thread in headless client!";
    private static final String TEXTURE_MARKER = "assets/lunar/";
    private static final String REACH_MARKER = "REACH_DISPLAY";
    private static final String REACH_FORMAT = "#.##";
    private static final String STAFF_MARKER = "Xray";

    private static final String[] MARKERS = {
            BLACKLIST_MARKER,
            POLLING_MARKER,
            TEXTURE_MARKER,
            REACH_MARKER,
            REACH_FORMAT,
            STAFF_MARKER
    };

    private static final byte[][] MARKER_BYTES = buildMarkerBytes();

    private static byte[][] buildMarkerBytes() {
        byte[][] result = new byte[MARKERS.length][];
        for (int i = 0; i < MARKERS.length; i++) {
            result[i] = MARKERS[i].getBytes(StandardCharsets.UTF_8);
        }
        return result;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (shouldSkip(className) || !mayMatch(classfileBuffer)) {
            return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            boolean changed = false;
            changed |= transformBlacklist(node);
            changed |= transformPollingrate(node);
            changed |= transformReach(node);
            changed |= transformStaff(node);
            changed |= transformTexturePack(node);

            if (!changed) {
                return null;
            }

            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            Agent.log("transform error on " + className + ": " + t);
            return null;
        }
    }

    /** True when the raw class file contains at least one marker used by a hook. */
    static boolean mayMatch(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return false;
        }
        for (byte[] marker : MARKER_BYTES) {
            if (indexOf(buffer, marker) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldSkip(String className) {
        if (className == null) {
            return true;
        }
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("org/spongepowered/")
                || className.startsWith("net/weavemc/")
                || className.startsWith("me/zircta/")
                || className.startsWith("me/youded/")
                || className.startsWith("kotlin/")
                || className.startsWith("kotlinx/");
    }

    private static boolean transformBlacklist(ClassNode classNode) {
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (!isPublic(method.access) || isStatic(method.access)) {
                continue;
            }
            if (!method.desc.equals("(Lcom/google/gson/JsonElement;)V")) {
                continue;
            }
            if (!containsLdc(method, BLACKLIST_MARKER)) {
                continue;
            }

            clearMethod(method, new InsnNode(Opcodes.RETURN));
            Agent.log("blacklist patched: " + classNode.name + "." + method.name);
            changed = true;
        }
        return changed;
    }

    private static boolean transformPollingrate(ClassNode classNode) {
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (!isPublic(method.access)) {
                continue;
            }
            if (!method.name.equals("start") || !method.desc.equals("()V")) {
                continue;
            }
            if (!containsLdc(method, POLLING_MARKER)) {
                continue;
            }

            clearMethod(method, new InsnNode(Opcodes.RETURN));
            Agent.log("polling-rate patched: " + classNode.name + "." + method.name);
            changed = true;
        }
        return changed;
    }

    private static boolean transformTexturePack(ClassNode classNode) {
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (!isPublic(method.access) || !isStatic(method.access)) {
                continue;
            }
            if (!method.desc.equals("(Ljava/lang/String;)Z")) {
                continue;
            }
            if (!containsLdc(method, TEXTURE_MARKER)) {
                continue;
            }

            clearMethod(method, new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IRETURN));
            Agent.log("texture-pack patched: " + classNode.name + "." + method.name);
            changed = true;
        }
        return changed;
    }

    private static boolean transformReach(ClassNode classNode) {
        if (!hasLdc(classNode, REACH_MARKER) || !hasLdc(classNode, REACH_FORMAT)) {
            return false;
        }

        boolean changed = false;
        int patchedClampSites = 0;

        for (MethodNode method : classNode.methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof LdcInsnNode && REACH_FORMAT.equals(((LdcInsnNode) insn).cst)) {
                        method.instructions.set(insn, new LdcInsnNode("0.00"));
                        changed = true;
                    }
                }
                continue;
            }

            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!isMathMinDD(insn)) {
                    continue;
                }
                if (!precededByInterfaceReturnD(insn, 6)) {
                    continue;
                }

                method.instructions.set(insn, new InsnNode(Opcodes.POP2));
                patchedClampSites++;
                changed = true;
            }
        }

        if (changed) {
            Agent.log("reach patched: " + classNode.name + " (clamp sites=" + patchedClampSites + ")");
        }
        return changed;
    }

    private static boolean transformStaff(ClassNode classNode) {
        if (!hasLdc(classNode, STAFF_MARKER)) {
            return false;
        }

        // Legacy Lunar shape: one primitive boolean field with a trivial getter/setter.
        String boolField = findSingleBooleanField(classNode);
        if (boolField != null) {
            MethodNode getter = findFieldGetter(classNode, boolField);
            MethodNode setter = findFieldSetter(classNode, boolField);
            if (getter != null && setter != null) {
                clearMethod(getter, new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IRETURN));
                clearMethod(setter,
                        new VarInsnNode(Opcodes.ALOAD, 0),
                        new InsnNode(Opcodes.ICONST_1),
                        new FieldInsnNode(Opcodes.PUTFIELD, classNode.name, boolField, "Z"),
                        new InsnNode(Opcodes.RETURN));

                Agent.log("staff patched (legacy): " + classNode.name
                        + " (getter=" + getter.name + ", setter=" + setter.name
                        + ", field=" + boolField + ")");
                return true;
            }
        }

        // Current Lunar shape (Oct 2026): the gate is backed by a generic
        // config field, so there is no primitive boolean field to find. The
        // class still has exactly one public ()Z getter and one public (Z)V
        // setter, both touching the same config field.
        MethodNode getter = findConfigBooleanGetter(classNode);
        if (getter == null) {
            return false;
        }
        String configField = firstOwnFieldName(getter, classNode.name);
        MethodNode setter = findConfigBooleanSetter(classNode, configField);
        if (setter == null) {
            return false;
        }

        clearMethod(getter, new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IRETURN));

        boolean forcedArgument = false;
        for (AbstractInsnNode insn : setter.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.ILOAD && ((VarInsnNode) insn).var == 1) {
                setter.instructions.set(insn, new InsnNode(Opcodes.ICONST_1));
                forcedArgument = true;
                break;
            }
        }
        if (!forcedArgument) {
            clearMethod(setter, new InsnNode(Opcodes.RETURN));
        }

        Agent.log("staff patched (config): " + classNode.name
                + " (getter=" + getter.name + ", setter=" + setter.name
                + ", field=" + configField + ", forcedArgument=" + forcedArgument + ")");
        return true;
    }

    private static boolean isMathMinDD(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
            return false;
        }
        MethodInsnNode mi = (MethodInsnNode) insn;
        return "java/lang/Math".equals(mi.owner) && "min".equals(mi.name) && "(DD)D".equals(mi.desc);
    }

    private static boolean precededByInterfaceReturnD(AbstractInsnNode pivot, int lookback) {
        AbstractInsnNode cursor = pivot.getPrevious();
        int steps = 0;
        while (cursor != null && steps < lookback) {
            if (cursor.getOpcode() == Opcodes.INVOKEINTERFACE) {
                MethodInsnNode mi = (MethodInsnNode) cursor;
                if (mi.desc != null && mi.desc.endsWith(")D")) {
                    return true;
                }
            }
            cursor = cursor.getPrevious();
            steps++;
        }
        return false;
    }

    private static MethodNode findConfigBooleanGetter(ClassNode classNode) {
        MethodNode found = null;
        for (MethodNode method : classNode.methods) {
            if (!isPublic(method.access) || isStatic(method.access)) {
                continue;
            }
            if (!method.desc.equals("()Z")) {
                continue;
            }
            if (firstOwnFieldName(method, classNode.name) == null) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method;
        }
        return found;
    }

    private static MethodNode findConfigBooleanSetter(ClassNode classNode, String fieldName) {
        if (fieldName == null) {
            return null;
        }
        MethodNode found = null;
        for (MethodNode method : classNode.methods) {
            if (!isPublic(method.access) || isStatic(method.access)) {
                continue;
            }
            if (!method.desc.equals("(Z)V")) {
                continue;
            }
            if (!referencesField(method, classNode.name, fieldName)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method;
        }
        return found;
    }

    private static String firstOwnFieldName(MethodNode method, String owner) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (owner.equals(field.owner)) {
                    return field.name;
                }
            }
        }
        return null;
    }

    private static boolean referencesField(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (owner.equals(field.owner) && name.equals(field.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findSingleBooleanField(ClassNode classNode) {
        String found = null;
        for (FieldNode field : classNode.fields) {
            if (!"Z".equals(field.desc)) {
                continue;
            }
            if ((field.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = field.name;
        }
        return found;
    }

    private static MethodNode findFieldGetter(ClassNode classNode, String fieldName) {
        for (MethodNode method : classNode.methods) {
            if (!"()Z".equals(method.desc)) {
                continue;
            }
            if ((method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (loadsOnlyField(method, classNode.name, fieldName, "Z")) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode findFieldSetter(ClassNode classNode, String fieldName) {
        for (MethodNode method : classNode.methods) {
            if (!"(Z)V".equals(method.desc)) {
                continue;
            }
            if ((method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (storesOnlyField(method, classNode.name, fieldName, "Z")) {
                return method;
            }
        }
        return null;
    }

    private static boolean loadsOnlyField(MethodNode method, String owner, String name, String desc) {
        boolean sawGetfield = false;
        for (AbstractInsnNode insn : method.instructions) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue;
            }
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (op != Opcodes.GETFIELD) {
                    return false;
                }
                if (!field.owner.equals(owner) || !field.name.equals(name) || !field.desc.equals(desc)) {
                    return false;
                }
                sawGetfield = true;
            }
        }
        return sawGetfield;
    }

    private static boolean storesOnlyField(MethodNode method, String owner, String name, String desc) {
        boolean sawPutfield = false;
        for (AbstractInsnNode insn : method.instructions) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue;
            }
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (op != Opcodes.PUTFIELD) {
                    return false;
                }
                if (!field.owner.equals(owner) || !field.name.equals(name) || !field.desc.equals(desc)) {
                    return false;
                }
                sawPutfield = true;
            }
        }
        return sawPutfield;
    }

    private static boolean containsLdc(MethodNode method, String value) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode && value.equals(((LdcInsnNode) insn).cst)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLdc(ClassNode classNode, String value) {
        for (MethodNode method : classNode.methods) {
            if (containsLdc(method, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublic(int access) {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    private static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    private static void clearMethod(MethodNode method, AbstractInsnNode... body) {
        method.instructions.clear();
        method.localVariables.clear();
        method.exceptions.clear();
        method.tryCatchBlocks.clear();
        for (AbstractInsnNode insn : body) {
            method.instructions.add(insn);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        int last = haystack.length - needle.length;
        for (int i = 0; i <= last; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }
}
