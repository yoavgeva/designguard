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
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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
 * @param superCalls       for each method and constructor that calls a method with {@code invokespecial}, such as
 *                         {@code super.describe()} or a private method, the {@link #callKey(String, String, int) calls}:
 *                         they run exactly the method they name, not an override
 * @param restoredInterrupts for each method and constructor that calls {@code Thread.currentThread().interrupt()},
 *                         the {@link #callKey(String, String, int) calls} of {@code interrupt()}
 * @param consoleCalls     for each method and constructor that uses {@code System.out}, {@code System.err} or
 *                         {@code System.in}, the {@link ConsoleCalls calls} it makes on them
 * @param parameterCalls   for each method and constructor, the {@link ParameterCalls calls} it makes on its parameters
 * @param loops            the calls each method makes once per item: in a loop, or in a lambda it gives a stream
 * @param accessorChanges  for each {@code get}, {@code is} or {@code has} method that changes fields of its class, by
 *                         name and descriptor, the {@link AccessorChanges changes} it makes, lazy initialization aside
 * @param threadFactoryCalls for each method that creates thread factories for executors, the
 *                         {@link ThreadFactoryCalls calls} made in them
 */
record ClassFileFacts(int release, List<Reference> creations, List<Reference> switchLabels,
        List<Reference> namedClasses, Map<String, Set<String>> directCalls, Map<String, Set<String>> superCalls,
        Map<String, Set<String>> restoredInterrupts, Map<String, Set<String>> consoleCalls,
        Map<String, Set<String>> parameterCalls, Loops loops,
        Map<String, List<AccessorChanges.FieldChange>> accessorChanges, Map<String, Set<String>> threadFactoryCalls) {

    /** For a class without a class file to read. */
    static final ClassFileFacts UNREAD = new ClassFileFacts(0, List.of(), List.of(), List.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), new Loops(Map.of(), Map.of(), Map.of()), Map.of(), Map.of());

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
        superCalls = Map.copyOf(superCalls);
        restoredInterrupts = Map.copyOf(restoredInterrupts);
        consoleCalls = Map.copyOf(consoleCalls);
        parameterCalls = Map.copyOf(parameterCalls);
        accessorChanges = Map.copyOf(accessorChanges);
        threadFactoryCalls = Map.copyOf(threadFactoryCalls);
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
        Map<String, Integer> captured = Loops.capturedSlots(node);
        Map<MethodNode, Memo<Receivers>> analyses = node.methods.stream().collect(Collectors.toMap(method -> method,
                method -> new Memo<>(() -> Receivers.of(node.name, method))));
        return new ClassFileFacts(release, creations, switchLabels, namedClasses(reader),
                byMethod(node, ClassFileFacts::directCalls), byMethod(node, ClassFileFacts::superCalls),
                byMethod(node, ClassFileFacts::restoredInterrupts),
                byMethod(node, method -> ConsoleCalls.in(method, analyses.get(method))),
                byMethod(node, method -> ParameterCalls.in(method, analyses.get(method))),
                new Loops(byMethod(node, method -> Loops.callsInLoops(method, analyses.get(method))),
                        byMethod(node, Loops::iteratedLambdas),
                        byMethod(node, method -> Loops.lambdaBodyCalls(method, captured, analyses.get(method)))),
                AccessorChanges.of(node), ThreadFactoryCalls.in(node));
    }

    /** The non-virtual calls of methods the method makes, keyed with their line: {@code super.m()} and private methods. */
    private static Set<String> superCalls(MethodNode method) {
        Set<String> calls = new LinkedHashSet<>();
        int line = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
            }
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && !"<init>".equals(call.name)) {
                calls.add(callKey(Type.getObjectType(call.owner).getClassName(), call.name, line));
            }
        }
        return Set.copyOf(calls);
    }

    /** The calls of {@code Thread.currentThread().interrupt()}, keyed with their line. */
    private static Set<String> restoredInterrupts(MethodNode method) {
        Set<String> calls = new LinkedHashSet<>();
        int line = 0;
        AbstractInsnNode previous = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LineNumberNode number) {
                line = number.line;
            }
            if (instruction.getOpcode() >= 0) {
                if (isThreadCall(instruction, "interrupt") && isThreadCall(previous, "currentThread")) {
                    calls.add(callKey("java.lang.Thread", "interrupt", line));
                }
                previous = instruction;
            }
        }
        return Set.copyOf(calls);
    }

    private static boolean isThreadCall(@Nullable AbstractInsnNode instruction, String name) {
        return instruction instanceof MethodInsnNode call && "java/lang/Thread".equals(call.owner) && name.equals(call.name);
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

    /**
     * Calls made once per item. Keys are methods by name and descriptor; calls are {@link #callKey(String, String, int)
     * call keys} of instance calls, the ones that can reach a collaborator.
     *
     * @param calls        the instance calls each method makes inside a loop: a backward jump and what it repeats
     * @param lambdas      the lambdas and method references each method hands to a stream, {@code forEach} or a
     *                     similar per-item method, as {@code owner.name} plus descriptor
     * @param lambdaBodies the instance calls in each lambda body, a synthetic {@code lambda$} method
     */
    record Loops(Map<String, Set<String>> calls, Map<String, Set<String>> lambdas, Map<String, Set<String>> lambdaBodies) {

        private static final Set<String> PER_ITEM_OWNERS = Set.of("java/util/stream/Stream", "java/util/stream/IntStream",
                "java/util/stream/LongStream", "java/util/stream/DoubleStream", "java/lang/Iterable",
                "java/util/Collection", "java/util/List", "java/util/Set", "java/util/Map", "java/util/Iterator");
        private static final String LAMBDA_PREFIX = "lambda$";
        private static final Set<String> PER_ITEM_METHODS = Set.of("forEach", "forEachOrdered", "forEachRemaining", "map",
                "mapToObj", "mapToInt", "mapToLong", "mapToDouble", "flatMap", "filter", "peek", "anyMatch", "allMatch",
                "noneMatch", "takeWhile", "dropWhile", "removeIf", "replaceAll");

        Loops {
            calls = Map.copyOf(calls);
            lambdas = Map.copyOf(lambdas);
            lambdaBodies = Map.copyOf(lambdaBodies);
        }

        /**
         * The calls between a backward jump and its target that ask a collaborator, an object that is the same for
         * every item of that loop, about an item.
         */
        static Set<String> callsInLoops(MethodNode method, Memo<Receivers> analysis) {
            List<AbstractInsnNode> instructions = instructionsOf(method);
            int[] lines = linesOf(instructions);
            int fixedSlots = slotsOf(method, Type.getArgumentTypes(method.desc).length);
            List<LoopValues.Stretch> loops = IntStream.range(0, instructions.size())
                    .filter(index -> instructions.get(index) instanceof JumpInsnNode jump
                            && method.instructions.indexOf(jump.label) < index)
                    .mapToObj(index -> new LoopValues.Stretch(
                            method.instructions.indexOf(((JumpInsnNode) instructions.get(index)).label), index, fixedSlots))
                    .toList();
            if (loops.isEmpty()) {
                return Set.of();
            }
            Receivers receivers = analysis.get();
            return IntStream.range(0, instructions.size())
                    .filter(index -> isInstanceCall(instructions.get(index)) && loops.stream().anyMatch(loop -> loop.contains(index)
                            && new LoopValues(receivers, loop).asksSameObjectPerItem((MethodInsnNode) instructions.get(index))))
                    .mapToObj(index -> keyOf((MethodInsnNode) instructions.get(index), lines[index]))
                    .collect(Collectors.toSet());
        }

        /**
         * The lambdas, and method references bound to an object such as {@code repository::find}, passed straight on
         * to a per-item method such as {@code map}.
         */
        static Set<String> iteratedLambdas(MethodNode method) {
            List<AbstractInsnNode> instructions = instructionsOf(method);
            return IntStream.range(0, instructions.size())
                    .filter(index -> instructions.get(index) instanceof InvokeDynamicInsnNode call
                            && LAMBDA_METAFACTORY.equals(call.bsm.getOwner()) && call.bsmArgs.length > 1
                            && call.bsmArgs[1] instanceof Handle handle && takesItems(instructions, index)
                            && (handle.getName().startsWith(LAMBDA_PREFIX) || Type.getArgumentTypes(call.desc).length > 0))
                    .mapToObj(index -> (Handle) ((InvokeDynamicInsnNode) instructions.get(index)).bsmArgs[1])
                    .map(handle -> Type.getObjectType(handle.getOwner()).getClassName() + "." + handle.getName()
                            + handle.getDesc())
                    .collect(Collectors.toSet());
        }

        /**
         * The calls in a lambda body that ask a collaborator, a field or a value the lambda captured, about its item.
         * None for other methods.
         *
         * @param method   the method
         * @param captured how many values each lambda body of the class captures as parameters, by name and descriptor
         * @param analysis the method's data flow, computed when first needed
         */
        static Set<String> lambdaBodyCalls(MethodNode method, Map<String, Integer> captured, Memo<Receivers> analysis) {
            if (!method.name.startsWith(LAMBDA_PREFIX)) {
                return Set.of();
            }
            List<AbstractInsnNode> instructions = instructionsOf(method);
            int[] lines = linesOf(instructions);
            Receivers receivers = analysis.get();
            LoopValues.Stretch body = new LoopValues.Stretch(0, instructions.size() - 1,
                    slotsOf(method, captured.getOrDefault(method.name + method.desc, 0)));
            return IntStream.range(0, instructions.size())
                    .filter(index -> isInstanceCall(instructions.get(index))
                            && new LoopValues(receivers, body).asksSameObjectPerItem((MethodInsnNode) instructions.get(index)))
                    .mapToObj(index -> keyOf((MethodInsnNode) instructions.get(index), lines[index]))
                    .collect(Collectors.toSet());
        }

        /**
         * How many values each lambda body of the class captures as parameters, by name and descriptor: the values
         * the {@code invokedynamic} that makes it takes, less {@code this}, which a body that is an instance method
         * captures as itself.
         */
        static Map<String, Integer> capturedSlots(ClassNode node) {
            return node.methods.stream()
                    .flatMap(method -> instructionsOf(method).stream())
                    .filter(instruction -> instruction instanceof InvokeDynamicInsnNode call
                            && LAMBDA_METAFACTORY.equals(call.bsm.getOwner()) && call.bsmArgs.length > 1
                            && call.bsmArgs[1] instanceof Handle handle && node.name.equals(handle.getOwner())
                            && handle.getName().startsWith(LAMBDA_PREFIX))
                    .map(InvokeDynamicInsnNode.class::cast)
                    .collect(Collectors.toMap(call -> ((Handle) call.bsmArgs[1]).getName() + ((Handle) call.bsmArgs[1]).getDesc(),
                            call -> Type.getArgumentTypes(call.desc).length
                                    - (((Handle) call.bsmArgs[1]).getTag() == Opcodes.H_INVOKESTATIC ? 0 : 1),
                            (first, second) -> first));
        }

        /** The local slots that {@code this}, if any, and the method's first parameters take. */
        private static int slotsOf(MethodNode method, int parameters) {
            int self = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            return self + Arrays.stream(Type.getArgumentTypes(method.desc)).limit(parameters).mapToInt(Type::getSize).sum();
        }

        /** Whether the next call after the lambda is a per-item method it is the argument of. */
        private static boolean takesItems(List<AbstractInsnNode> instructions, int lambda) {
            return instructions.subList(lambda + 1, instructions.size()).stream()
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .findFirst()
                    .filter(call -> PER_ITEM_OWNERS.contains(call.owner) && PER_ITEM_METHODS.contains(call.name))
                    .isPresent();
        }

        private static boolean isInstanceCall(AbstractInsnNode instruction) {
            return instruction.getOpcode() == Opcodes.INVOKEVIRTUAL || instruction.getOpcode() == Opcodes.INVOKEINTERFACE;
        }

        private static String keyOf(MethodInsnNode call, int line) {
            return callKey(Type.getObjectType(call.owner).getClassName(), call.name, line);
        }

        private static List<AbstractInsnNode> instructionsOf(MethodNode method) {
            return StreamSupport.stream(method.instructions.spliterator(), false).toList();
        }

        /** The source line of each instruction: the last line number before it. */
        private static int[] linesOf(List<AbstractInsnNode> instructions) {
            int[] lines = new int[instructions.size()];
            int line = 0;
            for (int index = 0; index < instructions.size(); index++) {
                if (instructions.get(index) instanceof LineNumberNode number) {
                    line = number.line;
                }
                lines[index] = line;
            }
            return lines;
        }
    }

    /**
     * The calls a method makes on {@code System.out}, {@code System.err} or {@code System.in}, directly or through a
     * local that holds one: they write to or read from the console, unlike the same calls on a stream it is given.
     */
    static final class ConsoleCalls {

        private static final Set<String> CONSOLE_STREAMS = Set.of("out", "err", "in");

        private ConsoleCalls() {
        }

        /**
         * The method's calls on a console stream, keyed with their line.
         *
         * @param method   the method
         * @param analysis the method's data flow, computed when first needed
         * @return the {@link #callKey(String, String, int) calls}; none if it never reads a console stream
         */
        static Set<String> in(MethodNode method, Memo<Receivers> analysis) {
            if (StreamSupport.stream(method.instructions.spliterator(), false).noneMatch(ConsoleCalls::isConsole)) {
                return Set.of();
            }
            Receivers receivers = analysis.get();
            Set<String> calls = new LinkedHashSet<>();
            int line = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode number) {
                    line = number.line;
                }
                if (instruction instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                        && receivers.stackAt(call, Type.getArgumentTypes(call.desc).length).stream()
                                .anyMatch(source -> isConsole(source) || holdsConsole(receivers, source))) {
                    calls.add(callKey(Type.getObjectType(call.owner).getClassName(), call.name, line));
                }
            }
            return Set.copyOf(calls);
        }

        private static boolean holdsConsole(Receivers receivers, AbstractInsnNode source) {
            return source instanceof VarInsnNode load && receivers.storesOf(load).stream()
                    .anyMatch(store -> receivers.stackAt(store, 0).stream().anyMatch(ConsoleCalls::isConsole));
        }

        private static boolean isConsole(AbstractInsnNode instruction) {
            return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && "java/lang/System".equals(field.owner) && CONSOLE_STREAMS.contains(field.name);
        }
    }

    /**
     * The calls a method makes on one of its parameters while the parameter still holds what the caller passed, as
     * {@code storage.load()} in {@code count(Storage storage)} is: they run the implementation the caller chose, not
     * any the project has. Only calls that may dispatch to project code are kept: virtual and interface calls of
     * types outside the JDK.
     */
    static final class ParameterCalls {

        private ParameterCalls() {
        }

        /**
         * The method's calls on its parameters, keyed with their line.
         *
         * @param method   the method
         * @param analysis the method's data flow, computed when first needed
         * @return the {@link #callKey(String, String, int) calls}; none if it takes no objects
         */
        static Set<String> in(MethodNode method, Memo<Receivers> analysis) {
            boolean takesObjects = Arrays.stream(Type.getArgumentTypes(method.desc))
                    .anyMatch(type -> type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
            if (!takesObjects || StreamSupport.stream(method.instructions.spliterator(), false)
                    .noneMatch(ParameterCalls::mayDispatch)) {
                return Set.of();
            }
            int first = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            int last = (Type.getArgumentsAndReturnSizes(method.desc) >> 2) - 2 + first;
            Receivers receivers = analysis.get();
            Set<String> calls = new LinkedHashSet<>();
            int line = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode number) {
                    line = number.line;
                }
                if (mayDispatch(instruction) && isParameter(receivers, receivers.stackAt(instruction,
                        Type.getArgumentTypes(((MethodInsnNode) instruction).desc).length), first, last)) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    calls.add(callKey(Type.getObjectType(call.owner).getClassName(), call.name, line));
                }
            }
            return Set.copyOf(calls);
        }

        /** Whether every source loads a parameter, in the slots from first to last, that the method never reassigns. */
        private static boolean isParameter(Receivers receivers, Set<AbstractInsnNode> sources, int first, int last) {
            return !sources.isEmpty() && sources.stream().allMatch(source -> source instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD && load.var >= first && load.var <= last
                    && receivers.storesOf(load).isEmpty());
        }

        private static boolean mayDispatch(AbstractInsnNode instruction) {
            return instruction instanceof MethodInsnNode call
                    && (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE)
                    && !call.owner.startsWith("java/") && !call.owner.startsWith("javax/");
        }
    }

    /**
     * The calls each method makes inside the thread factories it creates for executors, as
     * {@code work -> new Thread(work, "checker")} and {@code Thread::new} are. ArchUnit counts the calls in a lambda's body
     * as the calls of the method that creates it; the class file keeps them apart.
     */
    static final class ThreadFactoryCalls {

        private static final String THREAD_FACTORY = "java/util/concurrent/ThreadFactory";

        private ThreadFactoryCalls() {
        }

        /**
         * Reads the class's methods.
         *
         * @param node the class
         * @return for each method that creates thread factories, by name and descriptor, the
         *         {@link #callKey(String, String, int) calls} they make, or the constructor they name
         */
        static Map<String, Set<String>> in(ClassNode node) {
            Map<String, MethodNode> methods = node.methods.stream()
                    .collect(Collectors.toMap(method -> method.name + method.desc, Function.identity(), (first, second) -> first));
            return node.methods.stream()
                    .map(method -> Map.entry(method.name + method.desc, made(node.name, method, methods)))
                    .filter(entry -> !entry.getValue().isEmpty())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
        }

        private static Set<String> made(String owner, MethodNode method, Map<String, MethodNode> methods) {
            Set<String> calls = new LinkedHashSet<>();
            int line = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LineNumberNode number) {
                    line = number.line;
                }
                if (instruction instanceof InvokeDynamicInsnNode call && LAMBDA_METAFACTORY.equals(call.bsm.getOwner())
                        && THREAD_FACTORY.equals(Type.getReturnType(call.desc).getInternalName()) && call.bsmArgs.length > 1
                        && call.bsmArgs[1] instanceof Handle handle) {
                    int at = line;
                    calls.addAll(Optional.ofNullable(owner.equals(handle.getOwner()) ? methods.get(handle.getName() + handle.getDesc()) : null)
                            .map(ThreadFactoryCalls::callsIn)
                            .orElseGet(() -> Set.of(callKey(Type.getObjectType(handle.getOwner()).getClassName(),
                                    handle.getName(), at))));
                }
            }
            return Set.copyOf(calls);
        }

        private static Set<String> callsIn(MethodNode body) {
            Set<String> calls = new LinkedHashSet<>();
            int line = 0;
            for (AbstractInsnNode instruction : body.instructions) {
                if (instruction instanceof LineNumberNode number) {
                    line = number.line;
                }
                if (instruction instanceof MethodInsnNode call) {
                    calls.add(callKey(Type.getObjectType(call.owner).getClassName(), call.name, line));
                }
            }
            return calls;
        }
    }
}
