package io.github.yoavgeva.designguard.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The fields each {@code get}, {@code is} or {@code has} method of a class changes on {@code this} or statically:
 * by assigning them, by changing the collection or atomic they hold, as {@code items.add(item)} or
 * {@code count.incrementAndGet()} do, or through a method of the class it calls. Lazy initialization isn't a change: a
 * change inside code that runs only while a field is still {@code null} or a flag still {@code false}, and that sets
 * that field, as {@code if (name == null) { name = load(); }} or {@code if (!loaded) { ...; loaded = true; }} do. So is
 * caching in a map the method checks first, or in an atomic reference whose update keeps the value or replaces it with
 * one made without it, and a field set only for a while: a flag the accessor sets both {@code true} and {@code false},
 * or a field it both sets and clears, itself or through the methods it calls.
 */
final class AccessorChanges {

    private static final Pattern ACCESSOR = Pattern.compile("(get|is|has)[A-Z].*");
    private static final String CONSTRUCTOR = "<init>";
    /** Methods of collections and atomics that set a value only if there is none, as caching does. */
    private static final Set<String> CACHING = Set.of("computeIfAbsent", "putIfAbsent", "compareAndSet",
            "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile", "compareAndExchange");
    /** Methods of {@code java.util} collections and atomics that change them; caching ones such as computeIfAbsent don't. */
    private static final Set<String> MUTATORS = Set.of("add", "addAll", "put", "putAll", "remove", "removeAll",
            "retainAll", "removeIf", "replaceAll", "clear", "set", "push", "pop", "poll", "offer", "addFirst", "addLast",
            "removeFirst", "removeLast", "sort", "merge", "compute", "lazySet", "getAndSet", "getAndIncrement",
            "getAndDecrement", "getAndAdd", "incrementAndGet", "decrementAndGet", "addAndGet",
            "updateAndGet", "getAndUpdate", "accumulateAndGet", "getAndAccumulate", "increment", "decrement", "reset");

    private final Map<String, Body> bodies;
    private final UpdateFunctions updates;

    /**
     * Analyzes the accessors of the class and the methods of the class they call, directly or not; other methods can't
     * change anything an accessor does.
     */
    private AccessorChanges(ClassNode node) {
        Map<String, MethodNode> methods = node.methods.stream()
                .collect(Collectors.toMap(method -> method.name + method.desc, method -> method, (first, second) -> first));
        this.updates = new UpdateFunctions(node.name, methods);
        Set<String> reached = reachedFromAccessors(node.name, methods);
        Map<String, Set<String>> reads = reached.stream().flatMap(key -> calledInClass(node.name, methods.get(key)))
                .filter(methods::containsKey)
                .distinct()
                .collect(Collectors.toMap(key -> key, key -> fieldsRead(node.name, methods.get(key))));
        this.bodies = reached.stream()
                .collect(Collectors.toMap(key -> key, key -> new Body(node.name, methods.get(key), reads)));
    }

    /** The accessors and the methods of the class they call, directly or through others, by name and descriptor. */
    private static Set<String> reachedFromAccessors(String owner, Map<String, MethodNode> methods) {
        Set<String> reached = methods.keySet().stream()
                .filter(key -> ACCESSOR.matcher(key).lookingAt())
                .collect(Collectors.toCollection(HashSet::new));
        Deque<String> pending = new ArrayDeque<>(reached);
        while (!pending.isEmpty()) {
            calledInClass(owner, methods.get(pending.pop()))
                    .filter(callee -> methods.containsKey(callee) && reached.add(callee))
                    .forEach(pending::push);
        }
        return reached;
    }

    /** The methods of the class the method calls, by name and descriptor. */
    private static Stream<String> calledInClass(String owner, MethodNode method) {
        return StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof MethodInsnNode call && owner.equals(call.owner))
                .map(instruction -> ((MethodInsnNode) instruction).name + ((MethodInsnNode) instruction).desc);
    }

    /**
     * The changes each accessor of the class makes, by name and descriptor; none for methods that change nothing.
     *
     * @param node the class
     * @return each accessor's changes, the first for each field, by line
     */
    static Map<String, List<FieldChange>> of(ClassNode node) {
        return new AccessorChanges(node).accessors();
    }

    private Map<String, List<FieldChange>> accessors() {
        Map<String, Set<String>> changed = throughCallees(Body::ownFields);
        Map<String, Set<String>> writes = throughCallees(Body::writeKinds);
        Map<String, Map<String, FieldChange>> unguarded = unguardedChanges(changed);
        return unguarded.entrySet().stream()
                .filter(entry -> ACCESSOR.matcher(entry.getKey()).lookingAt())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().values().stream()
                        .filter(change -> !isTemporary(change.field(), writes.get(entry.getKey())))
                        .sorted(Comparator.comparingInt(FieldChange::line).thenComparing(FieldChange::field))
                        .toList()))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Whether the writes set the field both {@code true} and {@code false}, or both to a value and to {@code null}. */
    private static boolean isTemporary(String field, Set<String> writes) {
        return writes.contains(Written.FALSE.of(field)) && writes.contains(Written.TRUE.of(field))
                || writes.contains(Written.NULL.of(field)) && writes.contains(Written.VALUE.of(field));
    }

    /**
     * What each method has itself or through the methods of the class it calls, as the fields it changes, guarded or
     * not.
     */
    private Map<String, Set<String>> throughCallees(Function<Body, Set<String>> own) {
        Map<String, Set<String>> changed = new ConcurrentHashMap<>();
        bodies.forEach((key, body) -> changed.put(key, new HashSet<>(own.apply(body))));
        boolean grew = true;
        while (grew) {
            grew = bodies.entrySet().stream()
                    .map(entry -> entry.getValue().callsMade().stream()
                            .map(call -> changed.get(entry.getKey()).addAll(changed.getOrDefault(call.subject(), Set.of())))
                            .reduce(false, Boolean::logicalOr))
                    .reduce(false, Boolean::logicalOr);
        }
        return changed;
    }

    /** The changes each method makes outside lazy initialization, itself or through the methods it calls. */
    private Map<String, Map<String, FieldChange>> unguardedChanges(Map<String, Set<String>> changed) {
        Map<String, Map<String, FieldChange>> unguarded = new ConcurrentHashMap<>();
        bodies.forEach((key, body) -> {
            Map<String, FieldChange> own = new ConcurrentHashMap<>();
            body.changesMade().stream().filter(change -> !body.isLazy(change.index(), changed))
                    .forEach(change -> keepFirst(own, new FieldChange(change.subject(), change.line(), "")));
            unguarded.put(key, own);
        });
        boolean grew = true;
        while (grew) {
            grew = bodies.entrySet().stream()
                    .map(entry -> spreadFromCallees(entry.getValue(), unguarded.get(entry.getKey()), unguarded, changed))
                    .reduce(false, Boolean::logicalOr);
        }
        return unguarded;
    }

    /** Adds to the method's changes those its unguarded calls make; whether it added a field. */
    private static boolean spreadFromCallees(Body body, Map<String, FieldChange> changes,
            Map<String, Map<String, FieldChange>> unguarded, Map<String, Set<String>> changed) {
        return body.callsMade().stream()
                .filter(call -> !body.isLazy(call.index(), changed))
                .flatMap(call -> unguarded.getOrDefault(call.subject(), Map.of()).values().stream()
                        .map(change -> new FieldChange(change.field(), call.line(),
                                call.subject().substring(0, call.subject().indexOf('(')))))
                .map(change -> keepFirst(changes, change))
                .reduce(false, Boolean::logicalOr);
    }

    /** Keeps the earliest change of each field; whether it added a field. */
    private static boolean keepFirst(Map<String, FieldChange> changes, FieldChange change) {
        FieldChange kept = changes.putIfAbsent(change.field(), change);
        if (kept != null && FieldChange.ORDER.compare(change, kept) < 0) {
            changes.put(change.field(), change);
        }
        return kept == null;
    }

    /** The fields of the class the method reads. */
    private static Set<String> fieldsRead(String owner, MethodNode method) {
        return StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof FieldInsnNode read && owner.equals(read.owner)
                        && (read.getOpcode() == Opcodes.GETFIELD || read.getOpcode() == Opcodes.GETSTATIC))
                .map(instruction -> ((FieldInsnNode) instruction).name)
                .collect(Collectors.toSet());
    }

    /** What a write sets a flag or reference to; a field set both ways while a method runs is temporary. */
    private enum Written {
        FALSE, TRUE, NULL, VALUE;

        /** The field's name with this kind of write, such as {@code name#NULL}. */
        String of(String field) {
            return field + "#" + name();
        }

        /** What a write of a flag sets it to, given the instructions its value may come from. */
        static Optional<Written> ofFlag(Set<Integer> opcodes) {
            if (Set.of(Opcodes.ICONST_1).equals(opcodes)) {
                return Optional.of(TRUE);
            }
            return Set.of(Opcodes.ICONST_0).equals(opcodes) ? Optional.of(FALSE) : Optional.empty();
        }

        /** What a write of a reference sets it to, given the instructions its value may come from. */
        static Optional<Written> ofReference(Set<Integer> opcodes) {
            if (Set.of(Opcodes.ACONST_NULL).equals(opcodes)) {
                return Optional.of(NULL);
            }
            return opcodes.isEmpty() || opcodes.contains(Opcodes.ACONST_NULL) ? Optional.empty() : Optional.of(VALUE);
        }
    }

    /**
     * A change of a field, or a call of a method of the class, in a method.
     *
     * @param subject the field's name, or the method's name and descriptor
     * @param index   the instruction's index
     * @param line    the source line
     */
    private record Event(String subject, int index, int line) {
    }

    /** What one method changes, calls and guards. */
    private final class Body {

        private final String owner;
        private final List<AbstractInsnNode> instructions;
        private final Receivers receivers;
        private final List<Event> changeEvents = new ArrayList<>();
        private final List<Event> cachingEvents = new ArrayList<>();
        private final List<Event> callEvents = new ArrayList<>();
        private final Set<String> temporary = new HashSet<>();
        private final Set<String> kinds = new HashSet<>();
        private final List<LazyGuards.Guard> guards;

        Body(String owner, MethodNode method, Map<String, Set<String>> reads) {
            this.owner = owner;
            this.instructions = StreamSupport.stream(method.instructions.spliterator(), false).toList();
            this.receivers = Receivers.of(owner, method);
            this.guards = LazyGuards.in(owner, instructions, receivers, reads);
            int line = 0;
            for (int index = 0; index < instructions.size(); index++) {
                AbstractInsnNode instruction = instructions.get(index);
                if (instruction instanceof LineNumberNode number) {
                    line = number.line;
                }
                record(instruction, index, line);
            }
        }

        /** The changes, without fields the method restores before it returns. */
        List<Event> changesMade() {
            return changeEvents.stream().filter(change -> !temporary.contains(change.subject())).toList();
        }

        List<Event> callsMade() {
            return callEvents;
        }

        /** How the method itself sets flags and references, as the field's name and how, such as {@code name#NULL}. */
        Set<String> writeKinds() {
            return kinds;
        }

        Set<String> ownFields() {
            return Stream.concat(changeEvents.stream(), cachingEvents.stream()).map(Event::subject).collect(Collectors.toSet());
        }

        /** Whether the instruction is in a guard whose code sets a field the guard tests, itself or through calls. */
        boolean isLazy(int index, Map<String, Set<String>> changed) {
            return guards.stream().filter(guard -> guard.contains(index)).anyMatch(guard -> Stream.concat(
                            Stream.concat(changeEvents.stream(), cachingEvents.stream())
                                    .filter(change -> guard.contains(change.index())).map(Event::subject),
                            callEvents.stream().filter(call -> guard.contains(call.index()))
                                    .flatMap(call -> changed.getOrDefault(call.subject(), Set.of()).stream()))
                    .anyMatch(guard.fields()::contains));
        }

        private void record(AbstractInsnNode instruction, int index, int line) {
            if (instruction instanceof FieldInsnNode write && owner.equals(write.owner)
                    && (write.getOpcode() == Opcodes.PUTFIELD || write.getOpcode() == Opcodes.PUTSTATIC)
                    && receivers.writesThis(write)) {
                changeEvents.add(new Event(write.name, index, line));
                recordTemporary(write);
            } else if (instruction instanceof MethodInsnNode call) {
                recordCall(call, index, line);
            }
        }

        private void recordCall(MethodInsnNode call, int index, int line) {
            int arguments = Type.getArgumentTypes(call.desc).length;
            boolean onThis = call.getOpcode() == Opcodes.INVOKESTATIC || receivers.isThis(receivers.stackAt(call, arguments));
            if (owner.equals(call.owner) && !CONSTRUCTOR.equals(call.name) && onThis) {
                callEvents.add(new Event(call.name + call.desc, index, line));
            } else if (call.owner.startsWith("java/util/") && call.getOpcode() != Opcodes.INVOKESTATIC
                    && (MUTATORS.contains(call.name) || CACHING.contains(call.name))) {
                boolean changes = MUTATORS.contains(call.name) && !updates.refreshes(call, receivers.stackAt(call, 0));
                List<Event> events = changes ? changeEvents : cachingEvents;
                ownField(receivers.stackAt(call, arguments)).ifPresent(field -> events.add(new Event(field, index, line)));
            }
        }

        /** Notes how the write sets a flag or reference, and whether it restores a value the method read from it. */
        private void recordTemporary(FieldInsnNode write) {
            Set<AbstractInsnNode> values = receivers.stackAt(write, 0);
            boolean restores = values.stream().anyMatch(value -> value instanceof VarInsnNode load
                    && receivers.storesOf(load).stream().anyMatch(store -> receivers.stackAt(store, 0).stream()
                            .anyMatch(saved -> saved instanceof FieldInsnNode read && read.name.equals(write.name)
                                    && read.getOpcode() != Opcodes.PUTFIELD && read.getOpcode() != Opcodes.PUTSTATIC)));
            if (restores) {
                temporary.add(write.name);
            }
            recordKind(write, values.stream().map(AbstractInsnNode::getOpcode).collect(Collectors.toSet()));
        }

        /** Notes whether the write sets the flag {@code true} or {@code false}, or the reference to a value or null. */
        private void recordKind(FieldInsnNode write, Set<Integer> opcodes) {
            boolean reference = Type.getType(write.desc).getSort() >= Type.ARRAY;
            Optional<Written> kind = "Z".equals(write.desc) ? Written.ofFlag(opcodes)
                    : reference ? Written.ofReference(opcodes) : Optional.empty();
            kind.ifPresent(written -> kinds.add(written.of(write.name)));
        }

        /** The field of {@code this}, or a static one of the class, every source reads, if there is one. */
        private Optional<String> ownField(Set<AbstractInsnNode> sources) {
            Set<String> fields = sources.stream()
                    .map(source -> source instanceof FieldInsnNode read && owner.equals(read.owner)
                            && (read.getOpcode() == Opcodes.GETSTATIC
                                    || read.getOpcode() == Opcodes.GETFIELD && receivers.isThis(receivers.stackAt(read, 0)))
                            ? read.name : "")
                    .collect(Collectors.toSet());
            return fields.size() == 1 && !fields.contains("") ? Optional.of(fields.iterator().next())
                    : Optional.empty();
        }

    }

    /**
     * A field an accessor changes.
     *
     * @param field   the field's name
     * @param line    the source line of the change, or of the call that makes it
     * @param through the method of the class that makes the change, or empty if the accessor does
     */
    record FieldChange(String field, int line, String through) {

        static final Comparator<FieldChange> ORDER = Comparator.comparingInt(FieldChange::line)
                .thenComparing(FieldChange::field).thenComparing(FieldChange::through);
    }
}
