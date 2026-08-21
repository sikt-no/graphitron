package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.ClasspathEntry;
import no.sikt.graphitron.rewrite.ClasspathEntry.Origin;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Covers the classpath classification decode's one job: turning Maven's untyped classpath view
 * ({@link Artifact}s, {@link Dependency}s, classpath element strings) into the classified
 * {@link ClasspathEntry} list, in the {@link DependencyVersionDecodeTest} mould. What a
 * hand-built artifact set cannot pin is Maven's resolution: whether the dependency trail is
 * populated in a real build is measured there (it is, and the plugin integration tier holds the
 * real-build pin); what this pins is the four {@link Origin} arms given each directness signal.
 */
class ClasspathClassificationDecodeTest {

    private static Artifact artifact(String groupId, String artifactId, Path file, List<String> trail) {
        var artifact = new DefaultArtifact(groupId, artifactId, "1.0", "compile", "jar", null,
            new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        if (trail != null) {
            artifact.setDependencyTrail(trail);
        }
        return artifact;
    }

    private static Dependency declared(String groupId, String artifactId) {
        var dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        return dependency;
    }

    private static Path touch(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, new byte[0]);
        return file.toAbsolutePath().normalize();
    }

    @Test
    void theFourArmsFromOneClasspath(@TempDir Path tmp) throws IOException {
        Path own = Files.createDirectories(tmp.resolve("own/target/classes")).toAbsolutePath().normalize();
        Path declaredJar = touch(tmp.resolve("repo"), "declared.jar");
        Path transitiveJar = touch(tmp.resolve("repo"), "transitive.jar");
        Path siblingClasses = Files.createDirectories(tmp.resolve("sibling/target/classes"))
            .toAbsolutePath().normalize();

        String root = "no.sikt:consumer:jar:1.0";
        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), declaredJar.toString(), transitiveJar.toString()),
            own,
            List.of(
                artifact("com.example", "declared", declaredJar,
                    List.of(root, "com.example:declared:jar:1.0")),
                artifact("io.example", "transitive", transitiveJar,
                    List.of(root, "com.example:declared:jar:1.0", "io.example:transitive:jar:1.0"))),
            List.of(declared("com.example", "declared")),
            Map.of(siblingClasses, "no.sikt:sibling"));

        assertThat(entries)
            .extracting(ClasspathEntry::path, ClasspathEntry::origin, ClasspathEntry::coordinate)
            .containsExactly(
                tuple(own, Origin.PROJECT, null),
                tuple(declaredJar, Origin.DECLARED, "com.example:declared"),
                tuple(transitiveJar, Origin.TRANSITIVE, "io.example:transitive"),
                tuple(siblingClasses, Origin.SIBLING, "no.sikt:sibling"));
    }

    @Test
    void anUnpopulatedTrailFallsBackToTheDeclaredJoin(@TempDir Path tmp) throws IOException {
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path declaredJar = touch(tmp.resolve("repo"), "declared.jar");
        Path transitiveJar = touch(tmp.resolve("repo"), "transitive.jar");

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), declaredJar.toString(), transitiveJar.toString()),
            own,
            List.of(
                artifact("com.example", "declared", declaredJar, null),
                artifact("io.example", "transitive", transitiveJar, null)),
            List.of(declared("com.example", "declared")),
            Map.of());

        assertThat(entries)
            .extracting(ClasspathEntry::origin, ClasspathEntry::coordinate)
            .containsExactly(
                tuple(Origin.PROJECT, null),
                tuple(Origin.DECLARED, "com.example:declared"),
                tuple(Origin.TRANSITIVE, "io.example:transitive"));
    }

    @Test
    void theJoinNeverReadsVersions(@TempDir Path tmp) throws IOException {
        // dependencyManagement rewrites versions between declaration and resolution, so a
        // version-including join would misclassify every managed dependency as transitive.
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path managedJar = touch(tmp.resolve("repo"), "managed.jar");

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), managedJar.toString()),
            own,
            List.of(artifact("com.example", "managed", managedJar, null)),
            List.of(declared("com.example", "managed")),
            Map.of());

        assertThat(entries)
            .extracting(ClasspathEntry::origin)
            .containsExactly(Origin.PROJECT, Origin.DECLARED);
    }

    @Test
    void aReactorModuleArrivingTransitivelyIsSibling(@TempDir Path tmp) throws IOException {
        // A reactor module dragged in through another dependency is still a sibling: offerable in
        // the census, rejected by the build naming the module to declare. SIBLING outranks
        // TRANSITIVE for reactor outputs, which is the arm's whole point.
        Path own = Files.createDirectories(tmp.resolve("own/target/classes")).toAbsolutePath().normalize();
        Path siblingClasses = Files.createDirectories(tmp.resolve("sibling/target/classes"))
            .toAbsolutePath().normalize();

        String root = "no.sikt:consumer:jar:1.0";
        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), siblingClasses.toString()),
            own,
            List.of(artifact("no.sikt", "sibling", siblingClasses,
                List.of(root, "com.example:middle:jar:1.0", "no.sikt:sibling:jar:1.0"))),
            List.of(),
            Map.of(siblingClasses, "no.sikt:sibling"));

        assertThat(entries)
            .extracting(ClasspathEntry::origin, ClasspathEntry::coordinate)
            .containsExactly(
                tuple(Origin.PROJECT, null),
                tuple(Origin.SIBLING, "no.sikt:sibling"));
    }

    @Test
    void anUnattributablePathStaysInTheCensus(@TempDir Path tmp) throws IOException {
        // Dropping an entry nobody accounts for would silently defeat the census and the rule it
        // feeds; keeping it as DECLARED preserves the pre-classification behavior for it.
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path stray = touch(tmp.resolve("stray"), "stray.jar");

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), stray.toString()),
            own,
            List.of(),
            List.of(),
            Map.of());

        assertThat(entries)
            .extracting(ClasspathEntry::origin, ClasspathEntry::coordinate)
            .containsExactly(tuple(Origin.PROJECT, null), tuple(Origin.DECLARED, null));
    }

    @Test
    void aMissingReactorOutputIsNotFoldedIn(@TempDir Path tmp) throws IOException {
        // The reactor fold keeps the existence filter the path-based resolver had: a sibling that
        // has never been compiled contributes no entry.
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path never = tmp.resolve("never-built/target/classes").toAbsolutePath().normalize();

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString()),
            own,
            List.of(),
            List.of(),
            Map.of(never, "no.sikt:never-built"));

        assertThat(entries)
            .extracting(ClasspathEntry::path)
            .containsExactly(own);
    }
}
