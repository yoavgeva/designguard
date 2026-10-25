package io.github.yoavgeva.designguard.internal;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Whether an update function, as one given to {@code AtomicReference.updateAndGet}, refreshes a cache: on every path
 * it keeps the value it's given or replaces it with one made without it, as
 * {@code pair -> pair != null && pair.key().equals(key) ? pair : load(key)} does. A function that builds the new value
 * from the old, as {@code count -> count + 1} does, changes it.
 */
final class UpdateFunctions {

    private static final String ATOMIC_REFERENCE = "java/util/concurrent/atomic/AtomicReference";
    private static final Set<String> UPDATES = Set.of("updateAndGet", "getAndUpdate");
    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";

    private final String owner;
    private final Map<String, MethodNode> methods;

    /**
     * Judges the update functions a class implements in its own methods, as lambdas and method references are.
     *
     * @param owner   the class's internal name, e.g. {@code shop/Checkout}
     * @param methods its methods, by name and descriptor
     */
    UpdateFunctions(String owner, Map<String, MethodNode> methods) {
        this.owner = owner;
        this.methods = Map.copyOf(methods);
    }

    /**
     * Whether the call updates an atomic reference with a function of the class that refreshes a cache.
     *
     * @param call      a method call
     * @param functions what may have produced the call's last argument
     * @return whether it's such an update
     */
    boolean refreshes(MethodInsnNode call, Set<AbstractInsnNode> functions) {
        return ATOMIC_REFERENCE.equals(call.owner) && UPDATES.contains(call.name) && !functions.isEmpty()
                && functions.stream().allMatch(function -> function instanceof InvokeDynamicInsnNode lambda
                        && implementation(lambda).filter(method -> refreshes(owner, method)).isPresent());
    }

    /** The method of the class a lambda or method reference runs. */
    private Optional<MethodNode> implementation(InvokeDynamicInsnNode lambda) {
        return LAMBDA_FACTORY.equals(lambda.bsm.getOwner()) ? Arrays.stream(lambda.bsmArgs)
                .filter(argument -> argument instanceof Handle handle && owner.equals(handle.getOwner())
                        && handle.getTag() != Opcodes.H_NEWINVOKESPECIAL)
                .map(argument -> methods.get(((Handle) argument).getName() + ((Handle) argument).getDesc()))
                .filter(Objects::nonNull)
                .findFirst() : Optional.empty();
    }

    /**
     * Whether the function refreshes the value it takes last, as a lambda's code takes the update's argument after
     * what it captures.
     *
     * @param owner    the declaring class's internal name, e.g. {@code shop/Checkout}
     * @param function the method that implements the function
     * @return whether it keeps or replaces the value on every path, keeping it on some
     */
    private static boolean refreshes(String owner, MethodNode function) {
        Type[] parameters = Type.getArgumentTypes(function.desc);
        if (parameters.length == 0 || parameters[parameters.length - 1].getSort() < Type.ARRAY) {
            return false;
        }
        int current = ((function.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0)
                + Arrays.stream(parameters, 0, parameters.length - 1).mapToInt(Type::getSize).sum();
        try {
            List<@Nullable Frame<Origin>> frames = Arrays.asList(new Analyzer<>(new OriginInterpreter(current))
                    .analyze(owner, function));
            List<Set<Kind>> returned = IntStream.range(0, frames.size())
                    .filter(index -> function.instructions.get(index).getOpcode() == Opcodes.ARETURN)
                    .mapToObj(frames::get)
                    .filter(Objects::nonNull)
                    .map(frame -> frame.getStack(frame.getStackSize() - 1).kinds())
                    .toList();
            return returned.stream().noneMatch(kinds -> kinds.contains(Kind.DERIVED))
                    && returned.stream().anyMatch(kinds -> kinds.contains(Kind.CURRENT));
        } catch (AnalyzerException e) {
            return false;
        }
    }

    /** Where a value comes from, relative to the value being updated. */
    private enum Kind {
        /** The value being updated, unchanged. */
        CURRENT,
        /** A value made without it. */
        NEW,
        /** A value made from it. */
        DERIVED
    }

    /**
     * The kinds a value may be of, on the paths that reach an instruction.
     *
     * @param size  the number of stack or local slots the value takes
     * @param kinds the kinds it may be of
     */
    private record Origin(int size, Set<Kind> kinds) implements Value {

        Origin {
            kinds = Set.copyOf(kinds);
        }

        @Override
        public int getSize() {
            return size;
        }

        /** A value computed from the operands: derived if any of them may be the value being updated or made from it. */
        static Origin computed(int size, Stream<? extends Origin> operands) {
            boolean derived = operands.anyMatch(operand -> !Set.of(Kind.NEW).containsAll(operand.kinds()));
            return new Origin(size, Set.of(derived ? Kind.DERIVED : Kind.NEW));
        }

        /** The value on either of two paths that meet. */
        Origin or(Origin other) {
            Set<Kind> both = EnumSet.noneOf(Kind.class);
            both.addAll(kinds);
            both.addAll(other.kinds());
            return new Origin(Math.min(size, other.size()), both);
        }
    }

    /**
     * Follows the value being updated through the code: copies and casts keep it as it is, anything computed from it
     * is derived, and everything else is new. Sizes come from ASM's source interpreter, which ignores the operands.
     */
    private static final class OriginInterpreter extends Interpreter<Origin> {

        private static final SourceValue ANY = new SourceValue(1);
        private final SourceInterpreter sizes = new SourceInterpreter();
        private final int current;

        OriginInterpreter(int current) {
            super(Opcodes.ASM9);
            this.current = current;
        }

        @Override
        public @Nullable Origin newValue(@Nullable Type type) {
            if (Type.VOID_TYPE.equals(type)) {
                return null;
            }
            return new Origin(type == null ? 1 : type.getSize(), Set.of(Kind.NEW));
        }

        @Override
        public Origin newParameterValue(boolean isInstanceMethod, int local, Type type) {
            return new Origin(type.getSize(), Set.of(local == current ? Kind.CURRENT : Kind.NEW));
        }

        @Override
        public Origin newOperation(AbstractInsnNode instruction) {
            return new Origin(sizes.newOperation(instruction).getSize(), Set.of(Kind.NEW));
        }

        @Override
        public Origin copyOperation(AbstractInsnNode instruction, Origin value) {
            return value;
        }

        @Override
        public Origin unaryOperation(AbstractInsnNode instruction, Origin value) {
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value
                    : Origin.computed(sizes.unaryOperation(instruction, ANY).getSize(), Stream.of(value));
        }

        @Override
        public Origin binaryOperation(AbstractInsnNode instruction, Origin value1, Origin value2) {
            return Origin.computed(sizes.binaryOperation(instruction, ANY, ANY).getSize(), Stream.of(value1, value2));
        }

        @Override
        public Origin ternaryOperation(AbstractInsnNode instruction, Origin value1, Origin value2, Origin value3) {
            return Origin.computed(1, Stream.of(value1, value2, value3));
        }

        @Override
        public Origin naryOperation(AbstractInsnNode instruction, List<? extends Origin> values) {
            return Origin.computed(sizes.naryOperation(instruction, List.of()).getSize(), values.stream());
        }

        @Override
        public void returnOperation(AbstractInsnNode instruction, Origin value, Origin expected) {
            // Returning doesn't make a value.
        }

        @Override
        public Origin merge(Origin value1, Origin value2) {
            return value1.or(value2);
        }
    }
}
