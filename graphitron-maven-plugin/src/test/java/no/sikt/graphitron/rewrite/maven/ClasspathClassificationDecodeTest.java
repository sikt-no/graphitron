package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.ClasspathEntry.Origin;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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

    /**
     * The supplied stamp, which is the one thing in the tree that knows what a Maven checksum
     * sidecar is. Its <em>presence</em> is the signal, not its algorithm: a jar with one was
     * resolved from a repository, and a repository does not republish a coordinate under new
     * bytes, so the entry carries an identity and no consumer has to re-establish it. A locally
     * installed artifact gets no sidecar and keeps being hashed, which is exactly the population
     * that can change underneath a running session.
     */
    @Test
    void aResolvedJarCarriesItsSidecarsIdentity(@TempDir Path tmp) throws IOException {
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path resolved = touch(tmp.resolve("repo"), "resolved.jar");
        Path installed = touch(tmp.resolve("repo"), "installed.jar");
        sidecar(resolved, "b".repeat(40));

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), resolved.toString(), installed.toString()),
            own,
            List.of(),
            List.of(),
            Map.of());

        assertThat(entries)
            .extracting(ClasspathEntry::path, ClasspathEntry::suppliedStamp)
            .containsExactly(
                tuple(own, null),
                tuple(resolved, "sha1:" + "b".repeat(40)),
                tuple(installed, null));
    }

    /**
     * A stale sidecar is not trusted. Maven writes one once at download and never maintains it, so
     * installing over a release coordinate leaves it vouching for bytes that are gone; two stats
     * close that, and the jar falls back to being hashed. Rejecting is the only safe direction
     * here: a wrongly trusted stamp does not merely cost a read, it lets a partition be retained
     * against a jar whose classes have changed, and nothing later recomputes it.
     */
    @Test
    void aSidecarOlderThanItsJarIsNotTrusted(@TempDir Path tmp) throws IOException {
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path overwritten = touch(tmp.resolve("repo"), "overwritten.jar");
        sidecar(overwritten, "c".repeat(40));
        // The install that makes the sidecar a lie: same path, new bytes, later modification time.
        Files.write(overwritten, "rebuilt".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(overwritten, FileTime.fromMillis(
            Files.getLastModifiedTime(overwritten).toMillis() + 60_000L));

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), overwritten.toString()), own, List.of(), List.of(), Map.of());

        assertThat(entries)
            .extracting(ClasspathEntry::path, ClasspathEntry::suppliedStamp)
            .containsExactly(tuple(own, null), tuple(overwritten, null));
    }

    /**
     * A sidecar holding something other than a digest supplies nothing. The plugin decides what a
     * sidecar is; what a stamp may be spelled as is {@code SourceStamp}'s, so an unparseable value
     * is rejected there and arrives here as the absence it is.
     */
    @Test
    void anUnparseableSidecarSuppliesNothing(@TempDir Path tmp) throws IOException {
        Path own = Files.createDirectories(tmp.resolve("target/classes")).toAbsolutePath().normalize();
        Path jar = touch(tmp.resolve("repo"), "garbled.jar");
        sidecar(jar, "not-a-digest");

        var entries = AbstractRewriteMojo.classifyCompileClasspath(
            List.of(own.toString(), jar.toString()), own, List.of(), List.of(), Map.of());

        assertThat(entries)
            .extracting(ClasspathEntry::suppliedStamp)
            .containsExactly(null, null);
    }

    /**
     * Writes the sidecar Maven writes, and dates it the way Maven dates it: within a millisecond
     * or two of the download, which is to say not older than the jar.
     */
    private static void sidecar(Path jar, String digest) throws IOException {
        Path sidecar = jar.resolveSibling(jar.getFileName() + ".sha1");
        Files.writeString(sidecar, digest + "\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(sidecar, Files.getLastModifiedTime(jar));
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
