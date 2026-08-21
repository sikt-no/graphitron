package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One case per arm of the nameability rule's verdict table; see {@link ClasspathNameability}'s
 * class javadoc for the table itself. The probe reads resource names only, never class bytes, so
 * the fixtures are empty files at classfile paths.
 *
 * <p>Four of these are what make the rule a rule rather than a census read, each pinned as a
 * verdict rather than a message: a nested class in a kept entry is nameable (where a
 * census-absence predicate would have rejected the consumer's own class), a JDK class is
 * nameable, a {@code SIBLING} class is rejected naming the module, and a class carried by no
 * entry (the withdrawn plugin-classpath route among them) is rejected.
 */
@UnitTier
class ClasspathNameabilityTest {

    @Test
    void nestedClassInAKeptEntryIsNameable(@TempDir Path tmp) throws IOException {
        // The one to write first: the discarded census-absence predicate fails here silently,
        // because the census's top-level filter reads a nested class as absent while the codegen
        // loader resolves it. The author writes the source spelling; the resource is binary.
        Path classes = classesDirWith(tmp, "com/example/Outer$Inner.class");
        var check = new ClasspathNameability(List.of(ClasspathEntry.project(classes)));

        assertThat(check.verdictFor("com.example.Outer.Inner"))
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
        assertThat(check.verdictFor("com.example.Outer$Inner"))
            .as("the binary spelling is nameable too")
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
    }

    @Test
    void declaredJarClassIsNameable(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "com/example/lib/Library.class");
        var check = new ClasspathNameability(List.of(
            new ClasspathEntry(jar, ClasspathEntry.Origin.DECLARED, "com.example:library")));

        assertThat(check.verdictFor("com.example.lib.Library"))
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
    }

    @Test
    void jdkClassIsNameable(@TempDir Path tmp) throws IOException {
        // JDK classes are on every consumer's classpath by construction and are not a census
        // question; the classified list is non-empty so the inert arm is not what answers.
        Path classes = classesDirWith(tmp, "com/example/Own.class");
        var check = new ClasspathNameability(List.of(ClasspathEntry.project(classes)));

        assertThat(check.verdictFor("java.time.LocalDate"))
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
    }

    @Test
    void siblingClassIsRejectedNamingTheModule(@TempDir Path tmp) throws IOException {
        Path sibling = classesDirWith(tmp, "com/example/svc/FilmService.class");
        var check = new ClasspathNameability(List.of(
            new ClasspathEntry(sibling, ClasspathEntry.Origin.SIBLING, "no.sikt:example-service")));

        assertThat(check.verdictFor("com.example.svc.FilmService"))
            .isInstanceOfSatisfying(ClasspathNameability.Verdict.Rejected.class, rejected ->
                assertThat(rejected.reason())
                    .contains("reactor module no.sikt:example-service")
                    .containsIgnoringCase("declare a dependency on no.sikt:example-service"));
    }

    @Test
    void transitiveJarClassIsRejectedNamingTheCoordinate(@TempDir Path tmp) throws IOException {
        Path jar = jarWith(tmp, "io/netty/buffer/ByteBufAllocator.class");
        Path own = classesDirWith(tmp, "com/example/Own.class");
        var check = new ClasspathNameability(List.of(
            ClasspathEntry.project(own),
            new ClasspathEntry(jar, ClasspathEntry.Origin.TRANSITIVE, "io.netty:netty-buffer")));

        assertThat(check.verdictFor("io.netty.buffer.ByteBufAllocator"))
            .isInstanceOfSatisfying(ClasspathNameability.Verdict.Rejected.class, rejected ->
                assertThat(rejected.reason())
                    .as("the transitive probe names the coordinate that carries the class")
                    .contains("io.netty:netty-buffer")
                    .contains("does not declare"));
    }

    @Test
    void classCarriedByNoEntryIsRejected(@TempDir Path tmp) throws IOException {
        // The withdrawn plugin-classpath route lands here: a class supplied only under the
        // plugin's <dependencies> resolves through the codegen loader's parent chain but is
        // carried by no classpath entry, and the verdict does not consult any loader but the
        // platform's, so resolvability cannot rescue it.
        Path own = classesDirWith(tmp, "com/example/Own.class");
        var check = new ClasspathNameability(List.of(ClasspathEntry.project(own)));

        assertThat(check.verdictFor("com.example.plugin.OnlyOnPluginClasspath"))
            .isInstanceOfSatisfying(ClasspathNameability.Verdict.Rejected.class, rejected ->
                assertThat(rejected.reason()).contains("plugin"));
    }

    @Test
    void emptyClassifiedListIsInert(@TempDir Path tmp) {
        // A unit-tier RewriteContext carries no classpath roots; the rule cannot be enforced
        // against a classification nobody supplied, so every name is nameable.
        var check = new ClasspathNameability(List.of());

        assertThat(check.verdictFor("com.example.never.Compiled"))
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
        assertThat(ClasspathNameability.inert().verdictFor("com.example.never.Compiled"))
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
    }

    @Test
    void declaredEntryWinsOverATransitiveCopy(@TempDir Path tmp) throws IOException {
        // A class present under both a declared and a transitive entry is nameable: the kept
        // entries are probed first, matching the scanner's first-entry-wins dedup.
        Path declared = jarWith(tmp.resolve("declared"), "com/example/Both.class");
        Path transitive = jarWith(tmp.resolve("transitive"), "com/example/Both.class");
        var check = new ClasspathNameability(List.of(
            new ClasspathEntry(transitive, ClasspathEntry.Origin.TRANSITIVE, "com.example:far"),
            new ClasspathEntry(declared, ClasspathEntry.Origin.DECLARED, "com.example:near")));

        assertThat(check.verdictFor("com.example.Both"))
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
    }

    /** An empty file at {@code resource} under a fresh classes dir; the probe never reads bytes. */
    private static Path classesDirWith(Path tmp, String resource) throws IOException {
        Path classes = tmp.resolve("classes");
        Path file = classes.resolve(resource);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
        return classes;
    }

    private static Path jarWith(Path dir, String entryName) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve("fixture.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.closeEntry();
        }
        return jar;
    }
}
