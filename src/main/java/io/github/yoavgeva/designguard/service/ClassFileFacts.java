package io.github.yoavgeva.designguard.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * What ArchUnit doesn't import from a class file: the Java version it was compiled for, the classes its code creates
 * with {@code new}, the classes its switches match on, and every class it names. A {@code case Circle c} whose
 * {@code c} is unused names {@code Circle} only as a bootstrap argument of {@code SwitchBootstraps}, and
 * {@code case Suit.CLUBS} names {@code Suit} only inside a constant {@code EnumDesc}. Reading
 * {@code Limits.PAGE_SIZE} copies the constant's value into the code, and {@code Order.Status} names
 * {@code Order} only as the outer class, so the class file keeps {@code Limits} and {@code Order} only in its
 * constant pool. ArchUnit sees no dependency on any of them. ArchUnit also counts the calls in a lambda's body as
 * calls of the method that creates the lambda; the class file keeps them apart.
 *
 * @param release      the Java version the class was compiled for, e.g. 17; 0 if unread
 * @param creations    each {@code new}, in code order
 * @param switchLabels each class a {@code switch} matches on, at the line of the {@code switch}, in code order
 * @param namedClasses each class the constant pool names, at line 0: the class file doesn't say where
 * @param directCalls  for each method and constructor that creates lambdas, by name and descriptor such as
 *                     {@code <init>(I)V}, the {@link #callKey(String, String, int) calls} it makes itself, not those
 *                     in the lambdas; other methods make exactly the calls ArchUnit reports
 * @param nullGuardedReads for each method and constructor that compares anything with {@code null}, as lazy
 *                         initialization does, by name and descriptor, the fields it reads
 */
record ClassFileFacts(int release, List<Reference> creations, List<Reference> switchLabels,
        List<Reference> namedClasses, Map<String, Set<String>> directCalls, Map<String, Set<String>> nullGuardedReads) {

    /** For a class without a class file to read. */
    static final ClassFileFacts UNREAD = new ClassFileFacts(0, List.of(), List.of(), List.of(), Map.of(), Map.of());

    /** Java N writes class files of major version N + 44. */
    private static final int CLASS_FILE_VERSION_OFFSET = 44;
    private static final int MAJOR_VERSION_OFFSET = 6;
    private static final String SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps";
    private static final String CLASS_DESC = "java/lang/constant/ClassDesc";
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final int CONSTANT_CLASS = 7;

    ClassFileFacts {
        creations = List.copyOf(creations);
        switchLabels = List.copyOf(switchLabels);
        namedClasses = List.copyOf(namedClasses);
        directCalls = Map.copyOf(directCalls);
        nullGuardedReads = Map.copyOf(nullGuardedReads);
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
        return new ClassFileFacts(release, creations, switchLabels, namedClasses(reader),
                byMethod(node, ClassFileFacts::directCalls), byMethod(node, ClassFileFacts::nullGuardedReads));
    }

    /** The facts of the methods that have any, by name and descriptor. */
    private static Map<String, Set<String>> byMethod(ClassNode node, Function<MethodNode, Set<String>> facts) {
        return node.methods.stream()
                .map(method -> Map.entry(method.name + method.desc, facts.apply(method)))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
    }

    /**
     * The calls a method that creates lambdas makes itself, keyed with their line, plus a marker so the set is never
     * empty; none for other methods.
     */
    private static Set<String> directCalls(MethodNode method) {
        List<AbstractInsnNode> instructions = StreamSupport.stream(method.instructions.spliterator(), false).toList();
        boolean createsLambdas = instructions.stream().anyMatch(instruction -> instruction instanceof InvokeDynamicInsnNode call
                && LAMBDA_METAFACTORY.equals(call.bsm.getOwner()));
        if (!createsLambdas) {
            return Set.of();
        }
        Set<String> calls = new LinkedHashSet<>();
        calls.add(LAMBDA_METAFACTORY);
        int line = 0;
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
            }
            if (instruction instanceof MethodInsnNode call) {
                calls.add(callKey(Type.getObjectType(call.owner).getClassName(), call.name, line));
            }
        }
        return Set.copyOf(calls);
    }

    /** The fields the method reads, if it compares anything with {@code null}; else none. */
    private static Set<String> nullGuardedReads(MethodNode method) {
        List<AbstractInsnNode> instructions = StreamSupport.stream(method.instructions.spliterator(), false).toList();
        boolean comparesWithNull = instructions.stream()
                .anyMatch(instruction -> instruction.getOpcode() == Opcodes.IFNULL || instruction.getOpcode() == Opcodes.IFNONNULL);
        if (!comparesWithNull) {
            return Set.of();
        }
        return instructions.stream()
                .filter(instruction -> instruction.getOpcode() == Opcodes.GETFIELD || instruction.getOpcode() == Opcodes.GETSTATIC)
                .map(instruction -> ((FieldInsnNode) instruction).name)
                .collect(Collectors.toSet());
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

    /**
     * Identifies a call within a method.
     *
     * @param owner the class named in the call, e.g. {@code java.nio.file.Files}
     * @param name  the method's name, {@code <init>} for a constructor
     * @param line  the source line
     * @return the key, e.g. {@code java.nio.file.Files.readAllLines:12}
     */
    static String callKey(String owner, String name, int line) {
        return owner + "." + name + ":" + line;
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
