package io.github.yoavgeva.designguard.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Which of a method's parameters flow into which arguments of the calls it makes: a parameter, and every value made
 * from it, by arithmetic, a conversion such as {@code Long.valueOf(id)}, a call given it, a field or array element of
 * it, or a local it's stored in. A flow is {@code whole} when the argument is the parameter's object itself, passed on
 * or cast, rather than a value made from it. A call made on the parameter's object, as {@code form.setRole(role)} is,
 * flows into its receiver, at position -1. The method's own receiver, {@code this}, isn't a parameter.
 */
final class ParameterFlows {

    /** Parameters past this many aren't followed: their positions don't fit the bits a value keeps. */
    private static final int MOST = Long.SIZE;

    private ParameterFlows() {
    }

    /**
     * A parameter flowing into a call's argument.
     *
     * @param from       the parameter's position, from 0
     * @param owner      the called method's class, as an internal name such as {@code shop/OrderRepository}
     * @param name       the called method's name
     * @param descriptor the called method's descriptor
     * @param to         the argument's position, from 0, or -1 for the receiver
     * @param line       the call's line
     * @param whole      whether the argument is the parameter's object itself
     */
    record Flow(int from, String owner, String name, String descriptor, int to, int line, boolean whole) {
    }

    /**
     * The method's flows, in code order; none if it takes no parameters or the analysis can't follow it.
     *
     * @param owner  the declaring class's internal name, e.g. {@code shop/OrderController}
     * @param method the method
     * @return the flows
     */
    static List<Flow> in(String owner, MethodNode method) {
        if (Type.getArgumentTypes(method.desc).length == 0 || (method.access & Opcodes.ACC_ABSTRACT) != 0
                || Arrays.stream(method.instructions.toArray()).noneMatch(MethodInsnNode.class::isInstance)) {
            return List.of();
        }
        Frame<Held>[] frames;
        try {
            frames = new Analyzer<>(new HeldInterpreter(method)).analyze(owner, method);
        } catch (AnalyzerException e) {
            return List.of();
        }
        List<Flow> flows = new ArrayList<>();
        int line = 0;
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
            }
            if (instruction instanceof MethodInsnNode call && frames[index] != null) {
                flows.addAll(into(call, frames[index], line));
            }
        }
        return List.copyOf(flows);
    }

    /** The parameters that flow into a call's receiver and arguments, from the frame just before it. */
    private static List<Flow> into(MethodInsnNode call, Frame<Held> frame, int line) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        int first = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : -1;
        return IntStream.range(first, arguments).boxed()
                .flatMap(to -> {
                    Held value = frame.getStack(frame.getStackSize() - arguments + to);
                    return IntStream.range(0, MOST).filter(from -> (value.bits() & 1L << from) != 0)
                            .mapToObj(from -> new Flow(from, call.owner, call.name, call.desc, to, line,
                                    (value.whole() & 1L << from) != 0));
                })
                .toList();
    }

    private static @Nullable Held held(@Nullable BasicValue value, long bits) {
        return value == null ? null : new Held(value.getSize(), bits, 0);
    }

    /** A stand-in of the value's size: the basic interpreter only reads sizes and the instruction. */
    private static BasicValue basic(Held value) {
        return value.size() == 2 ? BasicValue.LONG_VALUE : BasicValue.REFERENCE_VALUE;
    }

    /** A value's size in slots, the parameters it's made from, one bit each, and those it is the object of. */
    private record Held(int size, long bits, long whole) implements Value {

        @Override
        public int getSize() {
            return size;
        }

        Held union(Held other) {
            return new Held(size, bits | other.bits(), whole | other.whole());
        }
    }

    /**
     * Gives each parameter its bit, and each value the bits of the values it's made from. A {@link BasicInterpreter}
     * says how big each value is.
     */
    private static final class HeldInterpreter extends Interpreter<Held> {

        private final BasicInterpreter sizes = new BasicInterpreter();
        /** The parameter position of each local slot that holds one, or -1. */
        private final int[] positions;

        HeldInterpreter(MethodNode method) {
            super(Opcodes.ASM9);
            this.positions = new int[Math.max(method.maxLocals, 1)];
            Arrays.fill(positions, -1);
            int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            Type[] parameters = Type.getArgumentTypes(method.desc);
            for (int position = 0; position < parameters.length && slot < positions.length; position++) {
                positions[slot] = position < MOST ? position : -1;
                slot += parameters[position].getSize();
            }
        }

        @Override
        public @Nullable Held newValue(@Nullable Type type) {
            if (type == Type.VOID_TYPE) {
                return null;
            }
            return new Held(type == null ? 1 : type.getSize(), 0, 0);
        }

        @Override
        public Held newParameterValue(boolean isInstanceMethod, int local, Type type) {
            int position = local < positions.length ? positions[local] : -1;
            long bit = position < 0 ? 0 : 1L << position;
            return new Held(type.getSize(), bit, bit);
        }

        @Override
        public Held newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            return held(sizes.newOperation(instruction), 0);
        }

        @Override
        public Held copyOperation(AbstractInsnNode instruction, Held value) {
            return value;
        }

        @Override
        public @Nullable Held unaryOperation(AbstractInsnNode instruction, Held value) throws AnalyzerException {
            Held made = held(sizes.unaryOperation(instruction, basic(value)), value.bits());
            return made != null && instruction.getOpcode() == Opcodes.CHECKCAST ? value : made;
        }

        @Override
        public @Nullable Held binaryOperation(AbstractInsnNode instruction, Held value1, Held value2)
                throws AnalyzerException {
            return held(sizes.binaryOperation(instruction, basic(value1), basic(value2)), value1.bits() | value2.bits());
        }

        @Override
        public @Nullable Held ternaryOperation(AbstractInsnNode instruction, Held value1, Held value2, Held value3) {
            return null;
        }

        @Override
        public @Nullable Held naryOperation(AbstractInsnNode instruction, List<? extends Held> values)
                throws AnalyzerException {
            long bits = values.stream().mapToLong(Held::bits).reduce(0, (first, second) -> first | second);
            return held(sizes.naryOperation(instruction, values.stream().map(ParameterFlows::basic).toList()), bits);
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction, Held value, Held expected) {
            // A returned value flows into no call.
        }

        @Override
        public Held merge(Held value1, Held value2) {
            return Objects.equals(value1, value2) ? value1 : value1.union(value2);
        }
    }
}
