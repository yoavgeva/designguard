package io.github.yoavgeva.designguard.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Where a method writes a property from its own old value: {@code product.setStock(product.getStock() - quantity)},
 * {@code this.balance = this.balance.subtract(amount)} or {@code count++}. The value written must be computed, with
 * arithmetic, {@code BigDecimal} or {@code BigInteger} arithmetic or string concatenation, from what the getter or the
 * field read on the same object returned ({@link ValueSources}). Copying the value back as it was isn't a change.
 */
final class DerivedWrites {

    private static final String SETTER = "set";

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
        List<AbstractInsnNode> instructions = List.of(method.instructions.toArray());
        if (instructions.stream().noneMatch(DerivedWrites::writesProperty)) {
            return List.of();
        }
        Receivers receivers = analysis.get();
        ValueSources values = new ValueSources(receivers);
        List<DerivedWrite> writes = new ArrayList<>();
        int line = 0;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
            }
            write(receivers, values, instruction, line).ifPresent(writes::add);
        }
        return List.copyOf(writes);
    }

    /** The derived write the instruction makes, if it's a setter call or a field write computed from the old value. */
    private static Optional<DerivedWrite> write(Receivers receivers, ValueSources values, AbstractInsnNode instruction,
            int line) {
        if (instruction instanceof MethodInsnNode call && isSetter(call)) {
            String property = property(call.name.substring(SETTER.length()));
            Set<AbstractInsnNode> receiver = receivers.stackAt(call, 1);
            return values.computedFrom(receivers.stackAt(call, 0), source -> isGetter(source, property)
                            && values.sameObject(receivers.stackAt(source, 0), receiver))
                    ? Optional.of(new DerivedWrite(call.owner, property, line, receivers.isThis(receiver)))
                    : Optional.empty();
        }
        if (instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD) {
            Set<AbstractInsnNode> receiver = receivers.stackAt(put, 1);
            return values.computedFrom(receivers.stackAt(put, 0), source -> source instanceof FieldInsnNode get
                            && get.getOpcode() == Opcodes.GETFIELD && get.name.equals(put.name)
                            && values.sameObject(receivers.stackAt(get, 0), receiver))
                    ? Optional.of(new DerivedWrite(put.owner, put.name, line, receivers.isThis(receiver)))
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean writesProperty(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && isSetter(call) || instruction.getOpcode() == Opcodes.PUTFIELD;
    }

    private static boolean isSetter(MethodInsnNode call) {
        return call.getOpcode() != Opcodes.INVOKESTATIC && call.name.length() > SETTER.length()
                && call.name.startsWith(SETTER) && Type.getArgumentTypes(call.desc).length == 1;
    }

    private static boolean isGetter(AbstractInsnNode source, String property) {
        return source instanceof MethodInsnNode call && Type.getArgumentTypes(call.desc).length == 0
                && Stream.of("get", "is").anyMatch(prefix -> call.name.startsWith(prefix)
                        && property(call.name.substring(prefix.length())).equals(property));
    }

    private static String property(String capitalized) {
        return capitalized.isEmpty() ? capitalized : Character.toLowerCase(capitalized.charAt(0)) + capitalized.substring(1);
    }
}
