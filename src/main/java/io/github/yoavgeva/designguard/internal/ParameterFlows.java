package io.github.yoavgeva.designguard.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
 * it, or a local it's stored in. The receiver, {@code this}, isn't a parameter.
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
     * @param to         the argument's position, from 0
     * @param line       the call's line
     */
    record Flow(int from, String owner, String name, String descriptor, int to, int line) {
    }

    /**
     * The method's flows, in code order; none if it takes no parameters or the analysis can't follow it.
     *
     * @param owner  the declaring class's internal name, e.g. {@code shop/OrderController}
     * @param method the method
     * @return the flows
     */
    static List<Flow> in(String owner, MethodNode method) {
        Type[] parameters = Type.getArgumentTypes(method.desc);
        if (parameters.length == 0 || (method.access & Opcodes.ACC_ABSTRACT) != 0
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
            Frame<Held> frame = frames[index];
            if (instruction instanceof MethodInsnNode call && frame != null) {
                int arguments = Type.getArgumentTypes(call.desc).length;
                for (int to = 0; to < arguments; to++) {
                    long bits = frame.getStack(frame.getStackSize() - arguments + to).bits();
                    for (int from = 0; from < MOST; from++) {
                        if ((bits & 1L << from) != 0) {
                            flows.add(new Flow(from, call.owner, call.name, call.desc, to, line));
                        }
                    }
                }
            }
        }
        return List.copyOf(flows);
    }

    /** A value's size in slots, and the parameters it's made from, one bit each. */
    private record Held(int size, long bits) implements Value {

        @Override
        public int getSize() {
            return size;
        }

        Held union(Held other) {
            return new Held(size, bits | other.bits());
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
            return new Held(type == null ? 1 : type.getSize(), 0);
        }

        @Override
        public Held newParameterValue(boolean isInstanceMethod, int local, Type type) {
            int position = local < positions.length ? positions[local] : -1;
            return new Held(type.getSize(), position < 0 ? 0 : 1L << position);
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
            return held(sizes.unaryOperation(instruction, basic(value)), value.bits());
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
            return held(sizes.naryOperation(instruction, values.stream().map(HeldInterpreter::basic).toList()), bits);
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction, Held value, Held expected) {
            // A returned value flows into no call.
        }

        @Override
        public Held merge(Held value1, Held value2) {
            return Objects.equals(value1, value2) ? value1 : value1.union(value2);
        }

        private static @Nullable Held held(@Nullable BasicValue value, long bits) {
            return value == null ? null : new Held(value.getSize(), bits);
        }

        /** A stand-in of the value's size: the basic interpreter only reads sizes and the instruction. */
        private static BasicValue basic(Held value) {
            return value.size() == 2 ? BasicValue.LONG_VALUE : BasicValue.REFERENCE_VALUE;
        }
    }
}
