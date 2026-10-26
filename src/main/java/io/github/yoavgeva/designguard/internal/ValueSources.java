package io.github.yoavgeva.designguard.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Walks back from a value to what it was made of, through a method's data flow: the operands of arithmetic and of
 * {@code BigDecimal} and {@code BigInteger} arithmetic, conversions and casts, and the stores into the locals it was
 * loaded from. Concatenating strings formats a value, as {@code "ROLE_" + name} does, rather than counting with it.
 */
final class ValueSources {

    /** How far back through a value's sources to look: far enough for any expression a write takes. */
    private static final int DEPTH = 24;
    private static final Set<String> NUMBERS = Set.of("java/math/BigDecimal", "java/math/BigInteger");
    private static final Set<String> NUMBER_ARITHMETIC = Set.of("add", "subtract", "multiply", "divide", "negate", "pow",
            "remainder", "max", "min");
    private static final Set<String> CONVERSIONS = Set.of("valueOf", "intValue", "longValue", "doubleValue", "floatValue",
            "shortValue", "byteValue", "toString");

    private final Receivers receivers;

    ValueSources(Receivers receivers) {
        this.receivers = receivers;
    }

    /**
     * Whether a value, whose producing instructions are {@code sources}, is computed from a source {@code read}
     * accepts: reached back through arithmetic at least once.
     */
    boolean computedFrom(Set<AbstractInsnNode> sources, Predicate<AbstractInsnNode> read) {
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
            boolean computes = step.computed() || computes(step.source());
            operands(step.source()).forEach(operand -> queue.addLast(new Step(operand, computes, step.depth() + 1)));
        }
        return false;
    }

    /** Whether two receivers are surely the same object: loads of one local with the same stores, or of {@code this}. */
    boolean sameObject(Set<AbstractInsnNode> first, Set<AbstractInsnNode> second) {
        Set<Object> origin = origin(first);
        return !origin.isEmpty() && origin.equals(origin(second));
    }

    /** The local a receiver comes from, with the stores that may have set it: empty if it's not one local. */
    private Set<Object> origin(Set<AbstractInsnNode> sources) {
        if (sources.size() != 1 || !(sources.iterator().next() instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD) {
            return Set.of();
        }
        Set<Object> origin = new HashSet<>(receivers.storesOf(load));
        origin.add(load.var);
        return origin;
    }

    /** Whether the instruction makes a new number from its operands. */
    private static boolean computes(AbstractInsnNode instruction) {
        return isArithmetic(instruction.getOpcode())
                || instruction instanceof MethodInsnNode call && NUMBERS.contains(call.owner)
                        && NUMBER_ARITHMETIC.contains(call.name);
    }

    private static boolean isArithmetic(int opcode) {
        return opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR;
    }

    /** The sources of the values an instruction takes, where they pass through: operands, and stores into a local. */
    private Stream<AbstractInsnNode> operands(AbstractInsnNode instruction) {
        if (instruction instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
            return receivers.storesOf(load).stream()
                    .flatMap(store -> store instanceof VarInsnNode ? receivers.stackAt(store, 0).stream() : Stream.of());
        }
        return IntStream.range(0, taken(instruction)).boxed()
                .flatMap(below -> receivers.stackAt(instruction, below).stream());
    }

    /** How many values the instruction takes off the stack, for those a value can pass through. */
    private static int taken(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (isArithmetic(opcode)) {
            return 2;
        }
        if (isConversion(opcode)) {
            return 1;
        }
        if (instruction instanceof MethodInsnNode call && (computes(call) || CONVERSIONS.contains(call.name))) {
            return Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        }
        return 0;
    }

    private static boolean isConversion(int opcode) {
        return opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S
                || opcode == Opcodes.CHECKCAST;
    }

    /** A value's source being looked at, whether arithmetic made the value from it, and how far back it is. */
    private record Step(AbstractInsnNode source, boolean computed, int depth) {
    }
}
