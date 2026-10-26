package io.github.yoavgeva.designguard.internal;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * What a plain {@code id} identifies, so its suggested type can be named after it: the project type a method names
 * after its verb, as {@code getPet(id)} and {@code partialUpdatePet(id)} name {@code Pet}; a Spring Data repository's
 * entity; the project type a role class is named for, as {@code OwnerDTO} and {@code OwnerFindService} are for
 * {@code Owner}; or else the declaring type itself.
 */
final class IdentifiedThings {

    private static final Pattern WORD_START = Pattern.compile("(?=[A-Z])");
    /** Where a method's name moves on to the key it looks by, as in {@code findVisitById}. */
    private static final Pattern BY = Pattern.compile("By(?=[A-Z])");
    /** Classes named for the role they play for a thing, rather than for the thing. */
    private static final Pattern ROLE = Pattern.compile(".*(Service|Repository|Controller|Resource|Endpoint|Handler"
            + "|Manager|Dao|Store|Mapper|Finder|Client|Facade|Impl|Dto|DTO|VM|Request|Response|Model|Nested)");
    private static final String ID = "Id";

    private final Set<String> typeNames;

    IdentifiedThings(JavaSources sources) {
        this.typeNames = sources.files().stream()
                .flatMap(file -> file.unit().stream())
                .flatMap(node -> node instanceof TypeDeclaration<?> type ? Stream.of(type.getNameAsString()) : Stream.empty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * What a field or record component of the owner identifies: for a role class, the project type its name has before
     * the role, as {@code OwnerDTO} and {@code AdminOwnerDTO} have {@code Owner}, starting or ending that part.
     */
    String ofMember(String owner) {
        Matcher role = ROLE.matcher(owner);
        if (!role.matches()) {
            return owner;
        }
        List<String> words = words(owner.substring(0, role.start(1)));
        Stream<String> prefixes = IntStream.iterate(words.size(), end -> end > 0, end -> end - 1)
                .mapToObj(end -> String.join("", words.subList(0, end)));
        Stream<String> suffixes = IntStream.range(1, words.size())
                .mapToObj(start -> String.join("", words.subList(start, words.size())));
        return Stream.concat(prefixes, suffixes)
                .filter(part -> typeNames.contains(part) && !ROLE.matcher(part).matches())
                .findFirst()
                .orElse(owner);
    }

    /** What a parameter of the callable identifies. */
    String ofParameter(Parameter parameter, CallableDeclaration<?> callable, String owner) {
        return named(callable.getNameAsString()).or(() -> repositoryEntity(parameter)).orElseGet(() -> ofMember(owner));
    }

    /** The longest run of words after the verb, before any {@code By...} and {@code Id}, that names a project type. */
    private Optional<String> named(String method) {
        List<String> words = words(BY.split(method, 2)[0]);
        if (words.isEmpty() || !Character.isLowerCase(words.get(0).charAt(0))) {
            return Optional.empty();
        }
        int end = !words.isEmpty() && ID.equals(words.get(words.size() - 1)) ? words.size() - 1 : words.size();
        return IntStream.range(1, end)
                .mapToObj(start -> String.join("", words.subList(start, end)))
                .filter(typeNames::contains)
                .findFirst();
    }

    private static Optional<String> repositoryEntity(Parameter parameter) {
        return Scopes.enclosingType(parameter)
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .stream()
                .flatMap(type -> Stream.concat(type.getExtendedTypes().stream(), type.getImplementedTypes().stream()))
                .filter(supertype -> supertype.getNameAsString().endsWith("Repository"))
                .flatMap(supertype -> supertype.getTypeArguments().stream().flatMap(List::stream).limit(1))
                .map(entity -> entity.isClassOrInterfaceType() ? entity.asClassOrInterfaceType().getNameAsString()
                        : entity.asString())
                .findFirst();
    }

    private static List<String> words(String name) {
        return Arrays.stream(WORD_START.split(name)).filter(word -> !word.isEmpty()).toList();
    }
}
