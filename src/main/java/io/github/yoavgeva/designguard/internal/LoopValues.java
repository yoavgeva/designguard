package io.github.yoavgeva.designguard.internal;

import java.util.Set;
import java.util.stream.IntStream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Which values in code run once per item, a loop's body or a lambda's, stay the same for every item, and which come
 * from the item. A collaborator stays the same: a field, a getter's result, a parameter, or a local set before the
 * loop. An item is what {@code next()} returns, an array element, a counter, or a lambda's own parameter.
 */
final class LoopValues {

    /** How many values back a value's origin is followed, as through {@code this.config.repository()}. */
    private static final int MAX_DEPTH = 8;
    /** Calls that take the next item from an iterator or a queue. */
    private static final Set<String> ITEM_CALLS = Set.of("next", "nextElement", "previous", "poll", "pop", "take",
            "removeFirst", "removeLast");
    private static final Set<Integer> CONSTANTS = Set.of(Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0,
            Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0,
            Opcodes.LCONST_1, Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1,
            Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC);
    private static final Set<Integer> ARRAY_READS = Set.of(Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD,
            Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD);

    private final Receivers receivers;
    private final Stretch stretch;

    LoopValues(Receivers receivers, Stretch stretch) {
        this.receivers = receivers;
        this.stretch = stretch;
    }

    /**
     * Whether the instance call asks the same object about each item: the object stays the same, and the call is given
     * an item or something read from one, such as its key, as {@code this.repository.find(order.id())} is. Reading the
     * next value of the same object, as {@code input.read()} does, or retrying with a new timeout, isn't asking about
     * an item.
     */
    boolean asksSameObjectPerItem(MethodInsnNode call) {
        int arguments = Type.getArgumentTypes(call.desc).length;
        return call.getOpcode() != Opcodes.INVOKESTATIC && isSame(receivers.stackAt(call, arguments), MAX_DEPTH)
                && IntStream.range(0, arguments).anyMatch(argument -> isFromItem(receivers.stackAt(call, argument), MAX_DEPTH));
    }

    /** Whether every value the sources may produce is the same for every item. */
    private boolean isSame(Set<AbstractInsnNode> sources, int depth) {
        return depth > 0 && !sources.isEmpty() && sources.stream().allMatch(source -> isSame(source, depth - 1));
    }

    private boolean isSame(AbstractInsnNode source, int depth) {
        int opcode = source.getOpcode();
        if (source instanceof VarInsnNode load && isLoad(load)) {
            return isSameLocal(load, depth);
        }
        if (source instanceof MethodInsnNode call) {
            return takesNothing(call) && (opcode == Opcodes.INVOKESTATIC || isSame(receivers.stackAt(call, 0), depth));
        }
        return opcode == Opcodes.GETSTATIC || CONSTANTS.contains(opcode)
                || (opcode == Opcodes.GETFIELD || opcode == Opcodes.CHECKCAST) && isSame(receivers.stackAt(source, 0), depth);
    }

    /** Whether the local holds a value fixed when the stretch starts, or one stored outside it from a value that is. */
    private boolean isSameLocal(VarInsnNode load, int depth) {
        Set<AbstractInsnNode> stores = receivers.storesOf(load);
        if (stores.isEmpty()) {
            return load.var < stretch.fixedSlots();
        }
        return stores.stream().allMatch(store -> isStore(store) && !stretch.contains(receivers.indexOf(store))
                && isSame(receivers.stackAt(store, 0), depth));
    }

    /**
     * Whether the value may be an item or come from one: what {@code next()} or a similar call returns in the stretch,
     * an array element read there, a counter it increments, a lambda's own parameter, or what a call, a field read, an
     * object created or arithmetic on such a value gives.
     */
    private boolean isFromItem(Set<AbstractInsnNode> sources, int depth) {
        return depth > 0 && sources.stream().anyMatch(source -> isFromItem(source, depth - 1));
    }

    private boolean isFromItem(AbstractInsnNode source, int depth) {
        int opcode = source.getOpcode();
        boolean inStretch = stretch.contains(receivers.indexOf(source));
        if (source instanceof VarInsnNode load && isLoad(load)) {
            return isItemLocal(load, depth);
        }
        if (source instanceof MethodInsnNode call) {
            return inStretch && ITEM_CALLS.contains(call.name) && takesNothing(call)
                    || isGivenItem(call, call.desc, depth) || call.getOpcode() != Opcodes.INVOKESTATIC
                    && isFromItem(receivers.stackAt(call, Type.getArgumentTypes(call.desc).length), depth);
        }
        if (source instanceof InvokeDynamicInsnNode joined) {
            return isGivenItem(joined, joined.desc, depth);
        }
        if (source instanceof TypeInsnNode creation && opcode == Opcodes.NEW) {
            return inStretch && isCreatedWithItem(creation, depth);
        }
        return inStretch && ARRAY_READS.contains(opcode)
                || isComputedFromItem(source, depth)
                || (opcode == Opcodes.GETFIELD || opcode == Opcodes.CHECKCAST) && isFromItem(receivers.stackAt(source, 0), depth);
    }

    /**
     * Whether the local is a lambda's own parameter, was set in the stretch from an item or as a counter, or holds an
     * object the stretch fills with items, as {@code key.append(order.id())} does.
     */
    private boolean isItemLocal(VarInsnNode load, int depth) {
        Set<AbstractInsnNode> stores = receivers.storesOf(load);
        if (stores.isEmpty() && load.var >= stretch.fixedSlots()) {
            return true;
        }
        boolean storedFromItem = stores.stream()
                .filter(store -> stretch.contains(receivers.indexOf(store)))
                .anyMatch(store -> store.getOpcode() == Opcodes.IINC || isFromItem(receivers.stackAt(store, 0), depth));
        return storedFromItem || load.getOpcode() == Opcodes.ALOAD && isFilledWithItems(load.var, depth);
    }

    /** Whether a call in the stretch on the object in the local is given an item, as {@code key.append(item)} is. */
    private boolean isFilledWithItems(int local, int depth) {
        return IntStream.rangeClosed(stretch.first(), stretch.last())
                .mapToObj(receivers::instructionAt)
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> receivers.stackAt(call, Type.getArgumentTypes(call.desc).length).stream()
                        .anyMatch(object -> object instanceof VarInsnNode load && load.var == local)
                        && isGivenItem(call, call.desc, depth));
    }

    /** Whether any argument the call or the {@code invokedynamic} with the descriptor takes is from an item. */
    private boolean isGivenItem(AbstractInsnNode call, String descriptor, int depth) {
        return IntStream.range(0, Type.getArgumentTypes(descriptor).length)
                .anyMatch(argument -> isFromItem(receivers.stackAt(call, argument), depth));
    }

    /** Whether the object created is given an item, as {@code new EntityKey(id)} is. */
    private boolean isCreatedWithItem(TypeInsnNode creation, int depth) {
        return IntStream.range(receivers.indexOf(creation) + 1, stretch.last() + 1)
                .mapToObj(receivers::instructionAt)
                .filter(instruction -> instruction instanceof MethodInsnNode call && "<init>".equals(call.name)
                        && receivers.stackAt(call, Type.getArgumentTypes(call.desc).length).contains(creation))
                .anyMatch(init -> isGivenItem(init, ((MethodInsnNode) init).desc, depth));
    }

    /** Whether the value is arithmetic on an item, as {@code index + 1} is. */
    private boolean isComputedFromItem(AbstractInsnNode operation, int depth) {
        int operands = operandsOf(operation.getOpcode());
        return IntStream.range(0, operands).anyMatch(operand -> isFromItem(receivers.stackAt(operation, operand), depth));
    }

    /** How many values an arithmetic or conversion instruction takes; 0 for other instructions. */
    private static int operandsOf(int opcode) {
        if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR) {
            return 2;
        }
        return opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S ? 1 : 0;
    }

    private static boolean isLoad(VarInsnNode instruction) {
        return instruction.getOpcode() >= Opcodes.ILOAD && instruction.getOpcode() <= Opcodes.ALOAD;
    }

    private static boolean isStore(AbstractInsnNode instruction) {
        return instruction.getOpcode() >= Opcodes.ISTORE && instruction.getOpcode() <= Opcodes.ASTORE;
    }

    private static boolean takesNothing(MethodInsnNode call) {
        return Type.getArgumentTypes(call.desc).length == 0;
    }

    /**
     * Code run once per item, by instruction index: a loop's body, or a whole lambda body.
     *
     * @param first      the first instruction
     * @param last       the last instruction
     * @param fixedSlots how many local slots, from the first, hold values fixed for every item when the stretch starts:
     *                   {@code this} and a method's parameters, or a lambda's captured values but not its item
     */
    record Stretch(int first, int last, int fixedSlots) {

        boolean contains(int index) {
            return first <= index && index <= last;
        }
    }
}
