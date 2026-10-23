package io.github.yoavgeva.designguard.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * What ArchUnit doesn't import from a class file: the Java version it was compiled for, the classes its code creates
 * with {@code new}, the classes its switches match on, and every class it names. A {@code case Circle c} whose
 * {@code c} is unused names {@code Circle} only as a bootstrap argument of {@code SwitchBootstraps}, and
 * {@code case Suit.CLUBS} names {@code Suit} only inside a constant {@code EnumDesc}. Reading
 * {@code Limits.PAGE_SIZE} copies the constant's value into the code, and {@code Order.Status} names
 * {@code Order} only as the outer class, so the class file keeps {@code Limits} and {@code Order} only in its
 * constant pool. ArchUnit sees no dependency on any of them.
 *
 * @param release      the Java version the class was compiled for, e.g. 17; 0 if unread
 * @param creations    each {@code new}, in code order
 * @param switchLabels each class a {@code switch} matches on, at the line of the {@code switch}, in code order
 * @param namedClasses each class the constant pool names, at line 0: the class file doesn't say where
 */
record ClassFileFacts(int release, List<Reference> creations, List<Reference> switchLabels,
        List<Reference> namedClasses) {

    /** For a class without a class file to read. */
    static final ClassFileFacts UNREAD = new ClassFileFacts(0, List.of(), List.of(), List.of());

    /** Java N writes class files of major version N + 44. */
    private static final int CLASS_FILE_VERSION_OFFSET = 44;
    private static final int MAJOR_VERSION_OFFSET = 6;
    private static final String SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps";
    private static final String CLASS_DESC = "java/lang/constant/ClassDesc";
    private static final int CONSTANT_CLASS = 7;

    ClassFileFacts {
        creations = List.copyOf(creations);
        switchLabels = List.copyOf(switchLabels);
        namedClasses = List.copyOf(namedClasses);
    }

    /**
     * Reads a class file.
     *
     * @param classFile the class file's bytes
     * @return its facts
     * @throws IllegalArgumentException if the class file is malformed or newer than ASM reads
     */
    static ClassFileFacts of(byte[] classFile) {
        ClassReader reader = new ClassReader(classFile);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        List<Reference> creations = new ArrayList<>();
        List<Reference> switchLabels = new ArrayList<>();
        for (MethodNode method : node.methods) {
            int line = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode number) {
                    line = number.line;
                }
                creation(instruction, line).ifPresent(creations::add);
                switchLabels(instruction, line).forEach(switchLabels::add);
            }
        }
        int release = reader.readUnsignedShort(MAJOR_VERSION_OFFSET) - CLASS_FILE_VERSION_OFFSET;
        return new ClassFileFacts(release, creations, switchLabels, namedClasses(reader));
    }

    /** The classes of the constant pool's class entries; a long or double takes two slots, the second one empty. */
    private static List<Reference> namedClasses(ClassReader reader) {
        char[] buffer = new char[reader.getMaxStringLength()];
        Stream<Type> named = IntStream.range(1, reader.getItemCount())
                .map(reader::getItem)
                .filter(offset -> offset > 0 && reader.readByte(offset - 1) == CONSTANT_CLASS)
                .mapToObj(offset -> Type.getObjectType(reader.readUTF8(offset, buffer)));
        return referencesTo(named, 0).toList();
    }

    private static Optional<Reference> creation(AbstractInsnNode instruction, int line) {
        if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
            return Optional.of(new Reference(Type.getObjectType(type.desc).getClassName(), line));
        }
        return Optional.empty();
    }

    private static Stream<Reference> switchLabels(AbstractInsnNode instruction, int line) {
        if (!(instruction instanceof InvokeDynamicInsnNode call) || !SWITCH_BOOTSTRAPS.equals(call.bsm.getOwner())) {
            return Stream.empty();
        }
        return referencesTo(Arrays.stream(call.bsmArgs).flatMap(ClassFileFacts::labelTypes), line);
    }

    /** References to the classes among the types, an array's element class included. */
    private static Stream<Reference> referencesTo(Stream<Type> types, int line) {
        return types.map(type -> type.getSort() == Type.ARRAY ? type.getElementType() : type)
                .filter(type -> type.getSort() == Type.OBJECT)
                .map(type -> new Reference(type.getClassName(), line));
    }

    /** The types a switch label names: a class, or the enum of an enum constant, built by {@code ClassDesc.of}. */
    private static Stream<Type> labelTypes(Object argument) {
        if (argument instanceof Type type) {
            return Stream.of(type);
        }
        if (!(argument instanceof ConstantDynamic constant)) {
            return Stream.empty();
        }
        List<Object> arguments = IntStream.range(0, constant.getBootstrapMethodArgumentCount())
                .mapToObj(constant::getBootstrapMethodArgument)
                .toList();
        if (arguments.size() == 2 && arguments.get(0) instanceof Handle factory && CLASS_DESC.equals(factory.getOwner())
                && arguments.get(1) instanceof String binaryName) {
            return Stream.of(Type.getObjectType(binaryName.replace('.', '/')));
        }
        return arguments.stream().flatMap(ClassFileFacts::labelTypes);
    }

    /**
     * A class the code names, and where.
     *
     * @param className the binary class name, e.g. {@code shop.Outer$Inner}
     * @param line      the source line, or 0 if the class file has no line numbers
     */
    record Reference(String className, int line) {
    }
}
