package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.ClasspathEntry.Origin;
import no.sikt.graphitron.rewrite.ClasspathNameability;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * What a goal run from inside one module makes of a declared sibling. Maven loads only that module,
 * so it resolves the sibling from the local repository, an installed jar that may be older than the
 * sibling's own {@code target/classes}. The run reads the sibling's own output in the jar's place,
 * which is the Quarkus model: a workspace module's {@code target/classes}, then its jar in
 * {@code target}, then the repository.
 */
class InstalledSiblingTest {

    @Test
    void theWorkspaceIsEveryModuleUnderTheOutermostAggregator(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp);

        assertThat(AbstractRewriteMojo.workspaceRoot(layout.b())).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(AbstractRewriteMojo.workspaceModules(tmp))
            .extracting(no.sikt.graphitron.model.config.WorkspaceModule::coordinate)
            .as("in declared order, depth first, the nested aggregator itself no module and its "
                + "module's groupId inherited from its parent")
            .containsExactly("ex:a", "ex:c", "ex:b");
    }

    @Test
    void anInstalledSiblingIsReadFromItsOwnOutputInPlace(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp);

        assertThat(layout.classpath())
            .extracting(ClasspathEntry::path, ClasspathEntry::origin, ClasspathEntry::coordinate,
                ClasspathEntry::kind)
            .as("the installed jar's position holds the sibling's classes; the undeclared nested "
                + "module is appended behind as a sibling the build refuses by coordinate")
            .containsExactly(
                tuple(layout.ownClasses(), Origin.PROJECT, null, ClasspathEntry.Kind.DIRECTORY),
                tuple(layout.siblingClasses(), Origin.REACTOR, "ex:a", ClasspathEntry.Kind.DIRECTORY),
                tuple(layout.nestedClasses(), Origin.SIBLING, "ex:c", ClasspathEntry.Kind.DIRECTORY));
    }

    @Test
    void eachWorkspaceEntryCarriesItsModule(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp);

        assertThat(layout.classpath())
            .extracting(entry -> entry.module() == null ? null : entry.module().coordinate())
            .as("the project's own output is its module too")
            .containsExactly("ex:b", "ex:a", "ex:c");
    }

    @Test
    void aClassAddedSinceTheInstallIsNameable(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp);

        assertThat(new ClasspathNameability(layout.classpath()).verdictFor("ex.a.Added"))
            .as("the sibling's own output carries it, under the coordinate the build declares")
            .isInstanceOf(ClasspathNameability.Verdict.Nameable.class);
    }

    @Test
    void aSiblingWithNoOutputKeepsItsInstalledCopy(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp);
        deleteTree(layout.siblingClasses());

        assertThat(layout.classify())
            .extracting(ClasspathEntry::path, ClasspathEntry::origin, ClasspathEntry::kind)
            .contains(tuple(layout.installedJar(), Origin.REACTOR, ClasspathEntry.Kind.SNAPSHOT));
    }

    @Test
    void aPackagedSiblingWithNoClassesIsReadFromItsJar(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp);
        deleteTree(layout.siblingClasses());
        Path packaged = Files.copy(layout.installedJar(),
            layout.siblingClasses().getParent().resolve("a-1.0-SNAPSHOT.jar"));

        assertThat(layout.classify())
            .extracting(ClasspathEntry::path, ClasspathEntry::origin)
            .contains(tuple(packaged.toAbsolutePath().normalize(), Origin.REACTOR));
    }

    @Test
    void aSiblingBuiltAtAReleaseVersionIsNoRelease(@TempDir Path tmp) throws IOException {
        var layout = Layout.of(tmp, "1.0");
        deleteTree(layout.siblingClasses());

        assertThat(layout.classify())
            .extracting(ClasspathEntry::path, ClasspathEntry::kind)
            .as("a module the reactor produces can be rebuilt under the same version")
            .contains(tuple(layout.installedJar(), ClasspathEntry.Kind.SNAPSHOT));
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * A workspace of {@code a}, a nested aggregator {@code group} holding {@code c}, and
     * {@code b}, the goal running in {@code b}, which declares {@code a} and not {@code c}.
     * {@code a} was installed carrying {@code A} and has since gained {@code Added}, compiled into
     * its own output only.
     */
    private record Layout(Path root, Path b, Path ownClasses, Path installedJar, Path siblingClasses,
                          Path nestedClasses, String version) {

        static Layout of(Path root) throws IOException {
            return of(root, "1.0-SNAPSHOT");
        }

        static Layout of(Path root, String version) throws IOException {
            Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>ex</groupId><artifactId>root</artifactId><version>%s</version>
                  <packaging>pom</packaging>
                  <modules><module>a</module><module>group</module><module>b</module></modules>
                </project>
                """.formatted(version));
            Path a = Files.createDirectories(root.resolve("a"));
            Files.writeString(a.resolve("pom.xml"), module("a", version));
            Path group = Files.createDirectories(root.resolve("group"));
            Files.writeString(group.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>ex</groupId><artifactId>group</artifactId><version>%s</version>
                  <packaging>pom</packaging>
                  <modules><module>c</module></modules>
                </project>
                """.formatted(version));
            Path c = Files.createDirectories(group.resolve("c"));
            Files.writeString(c.resolve("pom.xml"), module("c", version));
            Path b = Files.createDirectories(root.resolve("b"));
            Files.writeString(b.resolve("pom.xml"), module("b", version));

            Path ownClasses = Files.createDirectories(b.resolve("target/classes")).toAbsolutePath().normalize();
            Path siblingClasses = Files.createDirectories(a.resolve("target/classes/ex/a"))
                .getParent().getParent().toAbsolutePath().normalize();
            Files.createFile(siblingClasses.resolve("ex/a/A.class"));
            Files.createFile(siblingClasses.resolve("ex/a/Added.class"));
            Path nestedClasses = Files.createDirectories(c.resolve("target/classes")).toAbsolutePath().normalize();
            Path installedJar = Files.createDirectories(root.resolve("repo/ex/a/" + version))
                .resolve("a-" + version + ".jar").toAbsolutePath().normalize();
            try (OutputStream out = Files.newOutputStream(installedJar);
                 var zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("ex/a/A.class"));
                zip.closeEntry();
            }
            return new Layout(root, b.toAbsolutePath().normalize(), ownClasses, installedJar,
                siblingClasses, nestedClasses, version);
        }

        /** A module whose groupId is inherited from the parent it names. */
        private static String module(String artifactId, String version) {
            return """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>ex</groupId><artifactId>root</artifactId><version>%s</version></parent>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(version, artifactId);
        }

        List<ClasspathEntry> classpath() {
            return classify();
        }

        /** The classification as it stands on disk now. */
        List<ClasspathEntry> classify() {
            var artifact = new DefaultArtifact("ex", "a", version, "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
            artifact.setFile(installedJar.toFile());
            artifact.setDependencyTrail(List.of("ex:b:jar:" + version, "ex:a:jar:" + version));
            var declared = new Dependency();
            declared.setGroupId("ex");
            declared.setArtifactId("a");
            return AbstractRewriteMojo.classifyCompileClasspath(
                List.of(ownClasses.toString(), installedJar.toString()),
                ownClasses, List.of(artifact), List.of(declared),
                AbstractRewriteMojo.workspaceModules(AbstractRewriteMojo.workspaceRoot(b)));
        }
    }
}
