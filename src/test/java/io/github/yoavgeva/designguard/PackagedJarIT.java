package io.github.yoavgeva.designguard;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The jar projects depend on. It carries its own ASM under designguard's package, so an older ASM that a project's
 * build resolves, directly or through a BOM, can't replace the one that reads new class files.
 */
class PackagedJarIT {

    private static final Path TARGET = Path.of("target");
    private static final String OWN_ASM = "io.github.yoavgeva.designguard.internal.asm";

    @Test
    void carriesItsOwnAsm() throws IOException {
        try (JarFile jar = new JarFile(packagedJar().toFile())) {
            JavaClasses shipped = new ClassFileImporter().importJar(jar);

            assertThat(Stream.of(".ClassReader", ".tree.ClassNode", ".tree.analysis.Analyzer"))
                    .allMatch(name -> shipped.contain(OWN_ASM + name));
            classes().should().resideInAPackage("io.github.yoavgeva.designguard..").check(shipped);
            noClasses().should().dependOnClassesThat().resideInAPackage("org.objectweb.asm..").check(shipped);
            assertThat(jar.getEntry("META-INF/licenses/asm.txt")).isNotNull();
        }
    }

    @Test
    void makesNoProjectDependOnAsm() throws IOException {
        assertThat(Files.readString(Path.of("dependency-reduced-pom.xml")))
                .contains("<artifactId>archunit</artifactId>")
                .doesNotContain("<groupId>org.ow2.asm</groupId>");
    }

    private static Path packagedJar() throws IOException {
        try (Stream<Path> files = Files.list(TARGET)) {
            return files.filter(file -> file.getFileName().toString().matches("designguard-[^-]+(-SNAPSHOT)?\\.jar"))
                    .findFirst().orElseThrow();
        }
    }
}
