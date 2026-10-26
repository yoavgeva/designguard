package io.github.yoavgeva.designguard.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The code of a method that runs only while some fields of its class are unset, as lazy initialization does: the
 * then-block of {@code if (name == null)}, {@code if (!loaded)}, {@code if (index == UNSET)} or {@code if (count < 0)},
 * the rest of the method after {@code if (name != null) return name;}, or the else branch of
 * {@code name != null ? name : (name = load())}. A comparison, with a sentinel or with a remembered size, guards either
 * branch, and so does an {@code isEmpty()} check, as in {@code if (cache.isEmpty()) { fill(); }}. The tested value may
 * be read from the field directly, through a local, from a call on the field, as {@code cache.get(key)} is, or from a
 * method of the class that reads it, as {@code hasName()} may.
 */
final class LazyGuards {

    /** How many values back the field a guard tests is looked for. */
    private static final int MAX_DEPTH = 6;
    /**
     * Jumps past the code below when the value is set: not null, true, or not negative, as {@code if (count < 0)} tests
     * a sentinel by its sign. A comparison, with a sentinel or a remembered size, guards both ways.
     */
    private static final Set<Integer> SKIPS_WHEN_SET = Set.of(Opcodes.IFNONNULL, Opcodes.IFNE, Opcodes.IFGE, Opcodes.IFGT);
    private static final Set<Integer> EQUALITY_COMPARISONS = Set.of(Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
            Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE);
    /** Jumps to the code at the target when the value is unset. */
    private static final Set<Integer> JUMPS_WHEN_UNSET = Set.of(Opcodes.IFNULL, Opcodes.IFEQ);

    private final String owner;
    private final List<AbstractInsnNode> instructions;
    private final Receivers receivers;
    private final Map<String, Set<String>> reads;

    private LazyGuards(String owner, List<AbstractInsnNode> instructions, Receivers receivers,
            Map<String, Set<String>> reads) {
        this.owner = owner;
        this.instructions = instructions;
        this.receivers = receivers;
        this.reads = reads;
    }

    /**
     * Finds the guards of a method.
     *
     * @param owner        the class's internal name
     * @param instructions the method's instructions
     * @param receivers    where the method's values come from
     * @param reads        the fields of the class each of its methods reads, by name and descriptor
     * @return the guards, in code order
     */
    static List<Guard> in(String owner, List<AbstractInsnNode> instructions, Receivers receivers,
            Map<String, Set<String>> reads) {
        LazyGuards guards = new LazyGuards(owner, instructions, receivers, reads);
        return IntStream.range(0, instructions.size())
                .filter(index -> instructions.get(index) instanceof JumpInsnNode)
                .mapToObj(index -> guards.guardOf((JumpInsnNode) instructions.get(index), index))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * The guard a forward jump makes. Jumping past the code below when the value is set, as {@code IFNONNULL} does for
     * {@code if (name == null) { ... }}, guards that code. Jumping to code when the value is unset guards the code at
     * the target: the rest of the method if the code skipped returns, as after {@code if (name != null) return name;},
     * or the else branch, as in {@code name != null ? name : (name = load())}.
     */
    private Optional<Guard> guardOf(JumpInsnNode jump, int index) {
        int opcode = jump.getOpcode();
        int target = instructions.indexOf(jump.label);
        Set<String> fields = target > index ? testedFields(jump) : Set.of();
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        boolean comparison = isComparison(opcode);
        Optional<Guard> below = Optional.of(new Guard(index + 1, target - 1, fields));
        if (guardsCodeBelow(jump)) {
            return below;
        }
        if (!JUMPS_WHEN_UNSET.contains(opcode) && !comparison) {
            return Optional.empty();
        }
        Optional<Guard> atTarget = guardAtTarget(index, target, fields);
        return comparison ? atTarget.or(() -> below) : atTarget;
    }

    /**
     * The code at the target the jump guards: the rest of the method if the code it skips returns, or the else branch
     * if it ends with a jump past it.
     */
    private Optional<Guard> guardAtTarget(int index, int target, Set<String> fields) {
        Optional<AbstractInsnNode> last = lastInstruction(index + 1, target);
        boolean leaves = last.filter(instruction -> instruction.getOpcode() >= Opcodes.IRETURN
                && instruction.getOpcode() <= Opcodes.RETURN || instruction.getOpcode() == Opcodes.ATHROW).isPresent();
        if (leaves) {
            return Optional.of(new Guard(target, instructions.size() - 1, fields));
        }
        return last.filter(instruction -> instruction.getOpcode() == Opcodes.GOTO)
                .map(instruction -> instructions.indexOf(((JumpInsnNode) instruction).label))
                .filter(end -> end > target)
                .map(end -> new Guard(target, end - 1, fields));
    }

    /** Whether the jump skips the code below when the value is set, or when {@code isEmpty()} says it is. */
    private boolean guardsCodeBelow(JumpInsnNode jump) {
        return SKIPS_WHEN_SET.contains(jump.getOpcode()) || jump.getOpcode() == Opcodes.IFEQ && testsEmptiness(jump);
    }

    /** Whether the jump compares two values for equality; a bounds check such as {@code index < size} isn't a guard. */
    private static boolean isComparison(int opcode) {
        return EQUALITY_COMPARISONS.contains(opcode);
    }

    /** Whether the jump tests {@code isEmpty()}, as {@code if (cache.isEmpty()) { fill(); }} does. */
    private boolean testsEmptiness(JumpInsnNode jump) {
        return receivers.stackAt(jump, 0).stream()
                .anyMatch(source -> source instanceof MethodInsnNode call && "isEmpty".equals(call.name));
    }

    /** The fields the values a jump compares come from: one value, or both of a comparison. */
    private Set<String> testedFields(JumpInsnNode jump) {
        int values = isComparison(jump.getOpcode()) ? 2 : 1;
        return IntStream.range(0, values)
                .mapToObj(value -> testedFields(receivers.stackAt(jump, value), MAX_DEPTH))
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
    }

    /** The last real instruction between the indexes, skipping labels and line numbers. */
    private Optional<AbstractInsnNode> lastInstruction(int first, int end) {
        return IntStream.iterate(end - 1, index -> index >= first, index -> index - 1)
                .mapToObj(instructions::get)
                .filter(instruction -> instruction.getOpcode() >= 0)
                .findFirst();
    }

    private Set<String> testedFields(Set<AbstractInsnNode> sources, int depth) {
        if (depth == 0) {
            return Set.of();
        }
        return sources.stream().flatMap(source -> testedFields(source, depth - 1).stream()).collect(Collectors.toSet());
    }

    private Set<String> testedFields(AbstractInsnNode source, int depth) {
        if (isOwnRead(source)) {
            return Set.of(((FieldInsnNode) source).name);
        }
        if (source instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
            return receivers.storesOf(load).stream()
                    .filter(store -> store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE)
                    .flatMap(store -> testedFields(receivers.stackAt(store, 0), depth).stream())
                    .collect(Collectors.toSet());
        }
        if (source instanceof MethodInsnNode call) {
            return testedByCall(call, depth);
        }
        return source.getOpcode() == Opcodes.CHECKCAST ? testedFields(receivers.stackAt(source, 0), depth) : Set.of();
    }

    private boolean isOwnRead(AbstractInsnNode source) {
        return source instanceof FieldInsnNode read && owner.equals(read.owner)
                && (read.getOpcode() == Opcodes.GETFIELD || read.getOpcode() == Opcodes.GETSTATIC);
    }

    /**
     * The fields a method of the class reads, and those the object it is called on and its arguments come from, as
     * {@code cache.get(key)} and {@code entryOf(reference)} do.
     */
    private Set<String> testedByCall(MethodInsnNode call, int depth) {
        int values = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        Set<String> ofClass = owner.equals(call.owner) ? reads.getOrDefault(call.name + call.desc, Set.of()) : Set.of();
        Stream<String> ofValues = IntStream.range(0, values)
                .mapToObj(value -> testedFields(receivers.stackAt(call, value), depth))
                .flatMap(Set::stream);
        return Stream.concat(ofClass.stream(), ofValues).collect(Collectors.toSet());
    }

    /**
     * Code that runs only while some fields are unset.
     *
     * @param first  the first instruction's index
     * @param last   the last instruction's index
     * @param fields the fields the condition tests
     */
    record Guard(int first, int last, Set<String> fields) {

        Guard {
            fields = Set.copyOf(fields);
        }

        boolean contains(int index) {
            return first <= index && index <= last;
        }
    }
}
