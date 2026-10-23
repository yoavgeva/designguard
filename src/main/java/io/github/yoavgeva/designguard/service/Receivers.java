package io.github.yoavgeva.designguard.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Where the objects a method calls, captures and writes come from, found by ASM's data-flow analysis: {@code this},
 * a field of {@code this} (a collaborator), or anything else, such as a parameter or the item of a loop. Methods the
 * analysis can't follow have no known receivers.
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

    /** Whether the instance call is made on a field of {@code this}, as {@code this.repository.find(id)} is. */
    boolean callsFieldOfThis(MethodInsnNode call) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        return call.getOpcode() != Opcodes.INVOKESTATIC && fromFieldOfThis(call, arguments);
    }

    /** Whether the lambda or method reference captures a field of {@code this} first, as {@code repository::find} does. */
    boolean capturesFieldOfThis(InvokeDynamicInsnNode lambda) {
        int captured = Type.getArgumentTypes(lambda.desc).length;
        return captured > 0 && fromFieldOfThis(lambda, captured - 1);
    }

    /** Whether the field write goes to {@code this}, or to a static field of the class. */
    boolean writesThis(FieldInsnNode write) {
        return write.getOpcode() == Opcodes.PUTSTATIC
                || frameAt(write).filter(frame -> isThis(frame.getStack(frame.getStackSize() - 2).insns)).isPresent();
    }

    /** Whether the stack value below the given number of others was read from a field of {@code this}. */
    private boolean fromFieldOfThis(AbstractInsnNode instruction, int above) {
        return frameAt(instruction)
                .filter(frame -> frame.getStackSize() > above)
                .map(frame -> frame.getStack(frame.getStackSize() - 1 - above).insns)
                .filter(sources -> !sources.isEmpty() && sources.stream().allMatch(source -> source instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD && isThis(objectOf(field))))
                .isPresent();
    }

    private Set<AbstractInsnNode> objectOf(FieldInsnNode read) {
        return frameAt(read).map(frame -> frame.getStack(frame.getStackSize() - 1).insns).orElse(Set.of());
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
