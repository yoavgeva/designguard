package io.github.yoavgeva.designguard.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Where a method writes a property from its own old value: {@code product.setStock(product.getStock() - quantity)},
 * {@code this.balance = this.balance.subtract(amount)} or {@code count++}. The value written must be computed, with
 * arithmetic, {@code BigDecimal} or {@code BigInteger} arithmetic or string concatenation, from what the getter or the
 * field read on the same object returned. Copying the value back as it was isn't a change.
 */
final class DerivedWrites {

    /** How far back through a value's sources to look: far enough for any expression a write takes. */
    private static final int DEPTH = 24;
    private static final String SETTER = "set";
    private static final Set<String> NUMBERS = Set.of("java/math/BigDecimal", "java/math/BigInteger");
    private static final Set<String> NUMBER_ARITHMETIC = Set.of("add", "subtract", "multiply", "divide", "negate", "pow",
            "remainder", "max", "min");
    private static final Set<String> CONVERSIONS = Set.of("valueOf", "intValue", "longValue", "doubleValue", "floatValue",
            "shortValue", "byteValue", "toString");

    private DerivedWrites() {
    }

    /**
     * A property the method writes from its old value.
     *
     * @param owner    the internal name of the class whose setter or field is written, e.g. {@code shop/Product}
     * @param property the property's name, e.g. {@code stock}
     * @param line     the line of the write
     * @param ofThis   whether it's written on {@code this}, as an entity's own method does
     */
    record DerivedWrite(String owner, String property, int line, boolean ofThis) {
    }

    /**
     * The method's derived writes.
     *
     * @param method   the method
     * @param analysis the method's data flow, computed when first needed
     * @return the writes, in code order
     */
    static List<DerivedWrite> in(MethodNode method, Memo<Receivers> analysis) {
        if (Stream.of(method.instructions.toArray()).noneMatch(DerivedWrites::writesProperty)) {
            return List.of();
        }
        Receivers receivers = analysis.get();
        List<DerivedWrite> writes = new ArrayList<>();
        int line = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
            }
            if (instruction instanceof MethodInsnNode call && isSetter(call)) {
                String property = property(call.name.substring(SETTER.length()));
                Set<AbstractInsnNode> receiver = receivers.stackAt(call, 1);
                if (derived(receivers, receivers.stackAt(call, 0), source -> isGetter(source, property)
                        && sameObject(receivers, receivers.stackAt(source, 0), receiver))) {
                    writes.add(new DerivedWrite(call.owner, property, line, receivers.isThis(receiver)));
                }
            }
            if (instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD) {
                Set<AbstractInsnNode> receiver = receivers.stackAt(put, 1);
                if (derived(receivers, receivers.stackAt(put, 0), source -> source instanceof FieldInsnNode get
                        && get.getOpcode() == Opcodes.GETFIELD && get.name.equals(put.name)
                        && sameObject(receivers, receivers.stackAt(get, 0), receiver))) {
                    writes.add(new DerivedWrite(put.owner, put.name, line, receivers.isThis(receiver)));
                }
            }
        }
        return List.copyOf(writes);
    }

    private static boolean writesProperty(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && isSetter(call)
                || instruction.getOpcode() == Opcodes.PUTFIELD;
    }

    private static boolean isSetter(MethodInsnNode call) {
        return call.getOpcode() != Opcodes.INVOKESTATIC && call.name.length() > SETTER.length()
                && call.name.startsWith(SETTER) && Type.getArgumentTypes(call.desc).length == 1;
    }

    private static boolean isGetter(AbstractInsnNode source, String property) {
        return source instanceof MethodInsnNode call && Type.getArgumentTypes(call.desc).length == 0
                && (call.name.startsWith("get") && property(call.name.substring(3)).equals(property)
                        || call.name.startsWith("is") && property(call.name.substring(2)).equals(property));
    }

    private static String property(String capitalized) {
        return capitalized.isEmpty() ? capitalized : Character.toLowerCase(capitalized.charAt(0)) + capitalized.substring(1);
    }

    /**
     * Whether a value is computed from a source {@code read} accepts: reached back through arithmetic, at least once,
     * and through conversions, casts and locals.
     */
    private static boolean derived(Receivers receivers, Set<AbstractInsnNode> sources,
            java.util.function.Predicate<AbstractInsnNode> read) {
        Deque<Step> queue = new ArrayDeque<>();
        Set<AbstractInsnNode> seen = new HashSet<>();
        sources.forEach(source -> queue.add(new Step(source, false, 0)));
        while (!queue.isEmpty()) {
            Step step = queue.removeFirst();
            if (!seen.add(step.source()) || step.depth() > DEPTH) {
                continue;
            }
            if (read.test(step.source())) {
                if (step.computed()) {
                    return true;
                }
                continue;
            }
            boolean computes = computes(step.source());
            operands(receivers, step.source())
                    .forEach(operand -> queue.addLast(new Step(operand, step.computed() || computes, step.depth() + 1)));
        }
        return false;
    }

    /** Whether the instruction makes a new value from its operands: arithmetic, or concatenation. */
    private static boolean computes(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR
                || opcode == Opcodes.IINC
                || instruction instanceof MethodInsnNode call && NUMBERS.contains(call.owner)
                        && NUMBER_ARITHMETIC.contains(call.name)
                || instruction instanceof MethodInsnNode concat && "java/lang/String".equals(concat.owner)
                        && "concat".equals(concat.name)
                || instruction instanceof InvokeDynamicInsnNode dynamic && "makeConcatWithConstants".equals(dynamic.name);
    }

    /** The sources of the values an instruction takes, where they pass through: operands, and stores into a local. */
    private static Stream<AbstractInsnNode> operands(Receivers receivers, AbstractInsnNode instruction) {
        if (instruction instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
            return receivers.storesOf(load).stream()
                    .flatMap(store -> store instanceof VarInsnNode ? receivers.stackAt(store, 0).stream() : Stream.of());
        }
        int taken = taken(instruction);
        return java.util.stream.IntStream.range(0, taken).boxed()
                .flatMap(below -> receivers.stackAt(instruction, below).stream());
    }

    /** How many values the instruction takes off the stack, for those a value can pass through. */
    private static int taken(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR) {
            return 2;
        }
        if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S
                || opcode == Opcodes.CHECKCAST) {
            return 1;
        }
        if (instruction instanceof MethodInsnNode call && (computes(call) || CONVERSIONS.contains(call.name))) {
            int arguments = Type.getArgumentTypes(call.desc).length;
            return arguments + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic && computes(dynamic)) {
            return Type.getArgumentTypes(dynamic.desc).length;
        }
        return 0;
    }

    /** Whether two receivers are surely the same object: loads of one local, with the same stores, or of {@code this}. */
    private static boolean sameObject(Receivers receivers, Set<AbstractInsnNode> first, Set<AbstractInsnNode> second) {
        return origin(receivers, first).equals(origin(receivers, second)) && !origin(receivers, first).isEmpty();
    }

    /** The local a receiver comes from, with the stores that may have set it: empty if it's not one local. */
    private static Set<Object> origin(Receivers receivers, Set<AbstractInsnNode> sources) {
        if (sources.size() != 1 || !(sources.iterator().next() instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD) {
            return Set.of();
        }
        Set<Object> origin = new HashSet<>(receivers.storesOf(load));
        origin.add(load.var);
        return origin;
    }

    /** A value's source being looked at, whether arithmetic made the value from it, and how far back it is. */
    private record Step(AbstractInsnNode source, boolean computed, int depth) {
    }
}
