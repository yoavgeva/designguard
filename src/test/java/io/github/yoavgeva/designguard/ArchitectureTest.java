package io.github.yoavgeva.designguard;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.Test;

/**
 * The design rules this repository holds itself to. Model, view and controller layers, with checks living in a
 * service layer the controller drives.
 */
class ArchitectureTest {

    private static final String ROOT = "io.github.yoavgeva.designguard";
    private static final String CONTROLLER = "Controller";
    private static final String SERVICE = "Service";
    private static final String VIEW = "View";
    private static final String MODEL = "Model";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void layersFollowModelViewController() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer(CONTROLLER).definedBy(ROOT + ".controller..")
                .layer(SERVICE).definedBy(ROOT + ".service..")
                .layer(VIEW).definedBy(ROOT + ".view..")
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
                        ROOT + ".controller..", ROOT + ".service..", ROOT + ".view..", ROOT + ".model..")
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
