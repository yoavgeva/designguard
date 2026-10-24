package io.github.yoavgeva.designguard.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Where the values a method uses come from, found by ASM's data-flow analysis: for each value on the stack or in a
 * local, the instructions that may have produced or stored it. A parameter, or {@code this}, that was never stored
 * over has none. Methods the analysis can't follow have no known sources.
 */
final class Receivers {

    private final MethodNode method;
    /** One frame per instruction; null for instructions the analysis never reaches. */
    private final List<@Nullable Frame<SourceValue>> frames;

    private Receivers(MethodNode method, List<@Nullable Frame<SourceValue>> frames) {
        this.method = method;
        this.frames = frames;
    }

    /**
     * Analyzes a method.
     *
     * @param owner  the declaring class's internal name, e.g. {@code shop/Checkout}
     * @param method the method
     * @return its receivers
     */
    static Receivers of(String owner, MethodNode method) {
        try {
            Frame<SourceValue>[] analyzed = new Analyzer<>(new OriginInterpreter()).analyze(owner, method);
            return new Receivers(method, Collections.unmodifiableList(Arrays.asList(analyzed)));
        } catch (AnalyzerException e) {
            return new Receivers(method, List.of());
        }
    }

    /** Whether the field write goes to {@code this}, or to a static field of the class. */
    boolean writesThis(FieldInsnNode write) {
        return write.getOpcode() == Opcodes.PUTSTATIC || isThis(stackAt(write, 1));
    }

    /**
     * What may have produced a value on the stack just before the instruction runs.
     *
     * @param instruction the instruction
     * @param below       how many values are above it: 0 for the top of the stack
     * @return the producing instructions; none if unknown
     */
    Set<AbstractInsnNode> stackAt(AbstractInsnNode instruction, int below) {
        return frameAt(instruction)
                .filter(frame -> frame.getStackSize() > below)
                .map(frame -> frame.getStack(frame.getStackSize() - 1 - below).insns)
                .orElse(Set.of());
    }

    /** The stores that may have set the local the instruction loads; none if it still holds a parameter or {@code this}. */
    Set<AbstractInsnNode> storesOf(VarInsnNode load) {
        return frameAt(load).map(frame -> frame.getLocal(load.var).insns).orElse(Set.of());
    }

    /** The instruction's index in the method. */
    int indexOf(AbstractInsnNode instruction) {
        return method.instructions.indexOf(instruction);
    }

    /** The instruction at the index in the method. */
    AbstractInsnNode instructionAt(int index) {
        return method.instructions.get(index);
    }

    private boolean isThis(Set<AbstractInsnNode> sources) {
        return (method.access & Opcodes.ACC_STATIC) == 0 && !sources.isEmpty()
                && sources.stream().allMatch(source -> source instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 0);
    }

    private Optional<Frame<SourceValue>> frameAt(AbstractInsnNode instruction) {
        int index = method.instructions.indexOf(instruction);
        return index < frames.size() ? Optional.ofNullable(frames.get(index)) : Optional.empty();
    }

    /**
     * Tracks where each value came from through stack copies: {@code DUP} and {@code SWAP} move a value without making
     * a new one, so the copy keeps the original's source, as {@code this} in {@code this.count++} does.
     */
    private static final class OriginInterpreter extends SourceInterpreter {

        OriginInterpreter() {
            super(ASM9);
        }

        @Override
        public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            int opcode = instruction.getOpcode();
            return opcode >= DUP && opcode <= SWAP ? value : super.copyOperation(instruction, value);
        }
    }
}
