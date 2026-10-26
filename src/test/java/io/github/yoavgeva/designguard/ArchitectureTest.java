package io.github.yoavgeva.designguard;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The design rules this repository holds itself to. The public API is the root package, which drives the checks in
 * an internal package, with the data they share in a model package and output formats in a report package.
 */
class ArchitectureTest {

    private static final String ROOT = "io.github.yoavgeva.designguard";
    private static final String INTERNALS = ROOT + ".internal..";
    private static final String CONTROLLER = "Controller";
    private static final String SERVICE = "Service";
    private static final String VIEW = "View";
    private static final String MODEL = "Model";

    private static final ArchCondition<JavaCodeUnit> NAME_NO_INTERNAL_TYPE = new ArchCondition<>(
            "name no internal type") {
        @Override
        public void check(JavaCodeUnit unit, ConditionEvents events) {
            Stream.concat(Stream.of(unit.getReturnType()), unit.getParameterTypes().stream())
                    .map(JavaType::getAllInvolvedRawTypes)
                    .flatMap(Set::stream)
                    .filter(type -> type.getPackageName().startsWith(ROOT + ".internal"))
                    .forEach(type -> events.add(SimpleConditionEvent.violated(unit,
                            unit.getFullName() + " names " + type.getName())));
        }
    };

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void layersFollowModelViewController() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer(CONTROLLER).definedBy(ROOT)
                .layer(SERVICE).definedBy(INTERNALS)
                .layer(VIEW).definedBy(ROOT + ".report..")
                .layer(MODEL).definedBy(ROOT + ".model..")
                .whereLayer(CONTROLLER).mayNotBeAccessedByAnyLayer()
                .whereLayer(SERVICE).mayOnlyBeAccessedByLayers(CONTROLLER)
                .whereLayer(VIEW).mayOnlyBeAccessedByLayers(CONTROLLER)
                .whereLayer(MODEL).mayOnlyBeAccessedByLayers(CONTROLLER, SERVICE, VIEW)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void everyClassBelongsToALayer() {
        classes().should().resideInAnyPackage(
                        ROOT, INTERNALS, ROOT + ".report..", ROOT + ".model..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void publicApiNamesNoInternalType() {
        ArchRuleDefinition.codeUnits().that().arePublic().and().areDeclaredInClassesThat().arePublic()
                .and().areDeclaredInClassesThat().resideOutsideOfPackage(INTERNALS)
                .should(NAME_NO_INTERNAL_TYPE)
                .check(PRODUCTION_CLASSES);
        classes().that().arePublic().and().resideOutsideOfPackage(INTERNALS)
                .should().notBeAssignableTo(JavaClass.Predicates.resideInAPackage(INTERNALS))
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void layersHaveNoCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(PRODUCTION_CLASSES);
    }

    @Test
    void modelIsImmutable() {
        fields().that().areDeclaredInClassesThat().resideInAPackage(ROOT + ".model..")
                .should().beFinal()
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void libraryNeverPrintsToStandardStreams() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(PRODUCTION_CLASSES);
    }

    @Test
    void noGenericExceptionsAreThrown() {
        GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(PRODUCTION_CLASSES);
    }

    @Test
    void noJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(PRODUCTION_CLASSES);
    }

    @Test
    void noFieldInjection() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(PRODUCTION_CLASSES);
    }

    @Test
    void noDeprecatedApiUsage() {
        GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED.check(PRODUCTION_CLASSES);
    }
}
