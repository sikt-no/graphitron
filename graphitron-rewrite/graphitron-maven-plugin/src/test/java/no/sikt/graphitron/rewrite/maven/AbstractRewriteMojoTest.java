package no.sikt.graphitron.rewrite.maven;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the one-shot mojo's rendering of a {@code ValidationFailedException}: the failure message
 * must carry the per-error {@code file:line:col} detail, not just the bare count, and it must read
 * identically to what {@code DevMojo} renders for the same errors (parity is structural: both route
 * through {@link WatchErrorFormatter#format}).
 */
class AbstractRewriteMojoTest {

    @Test
    void validationFailureMessage_rendersPerErrorFileLineColDetail() {
        var errors = List.of(
            error("Query.foo", "Field 'Query.foo': missing @reference target",
                "src/main/resources/schema/query.graphqls", 12, 3),
            error("User.orders", "Field 'User.orders': not on a table-backed type",
                "src/main/resources/schema/user.graphqls", 42, 5));

        String msg = AbstractRewriteMojo.validationFailureMessage(errors);

        assertThat(msg)
            .contains("GraphQL schema validation failed:")
            // The source file and line:col coordinate of each error surfaces, not just a count.
            .contains("src/main/resources/schema/query.graphqls")
            .contains("12:3")
            .contains("Field 'Query.foo': missing @reference target")
            .contains("src/main/resources/schema/user.graphqls")
            .contains("42:5")
            .contains("Field 'User.orders': not on a table-backed type")
            // The kind summary line is present; the message is more than the bare count.
            .contains("2 error(s):");
    }

    @Test
    void validationFailureMessage_matchesDevLoopRenderer() {
        var errors = List.of(
            error("Query.foo", "Field 'Query.foo': missing @reference target",
                "src/main/resources/schema/query.graphqls", 12, 3));

        String oneShot = AbstractRewriteMojo.validationFailureMessage(errors);

        // Parity with DevMojo: the one-shot failure embeds the exact tree the dev loop renders for
        // the same errors (null previous-key set => no delta line), so the two surfaces cannot drift.
        assertThat(oneShot).contains(WatchErrorFormatter.format(errors, null));
    }

    @Test
    void collectExistingDirs_classpathAndSourceRootsCoverSameReactorProjects(@TempDir Path root) throws Exception {
        // Two reactor projects, each with an existing target/classes and src/main/java.
        // The shared traversal must visit both for both extractors, so a class
        // scanned for completion always has its source root walked (R351).
        var projects = new ArrayList<MavenProject>();
        var expectedClasses = new ArrayList<Path>();
        var expectedSources = new ArrayList<Path>();
        for (String name : List.of("module-a", "module-b")) {
            Path classes = Files.createDirectories(root.resolve(name).resolve("target/classes"));
            Path src = Files.createDirectories(root.resolve(name).resolve("src/main/java"));
            var project = new MavenProject();
            var build = new Build();
            build.setOutputDirectory(classes.toString());
            project.setBuild(build);
            project.addCompileSourceRoot(src.toString());
            projects.add(project);
            expectedClasses.add(classes.toAbsolutePath().normalize());
            expectedSources.add(src.toAbsolutePath().normalize());
        }

        var classpathRoots = AbstractRewriteMojo.collectExistingDirs(projects,
            p -> p.getBuild() == null ? List.of() : List.of(p.getBuild().getOutputDirectory()));
        var sourceRoots = AbstractRewriteMojo.collectExistingDirs(projects,
            p -> p.getCompileSourceRoots());

        assertThat(classpathRoots).containsExactlyElementsOf(expectedClasses);
        assertThat(sourceRoots).containsExactlyElementsOf(expectedSources);
        // Structural parity: one root per project on both paths, same project set.
        assertThat(sourceRoots).hasSameSizeAs(classpathRoots);
    }

    @Test
    void collectExistingDirs_skipsMissingAndNullDirectories(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real/src/main/java"));
        var withReal = new MavenProject();
        withReal.addCompileSourceRoot(real.toString());
        withReal.addCompileSourceRoot(root.resolve("does/not/exist").toString());
        var empty = new MavenProject(); // no source roots

        var roots = AbstractRewriteMojo.collectExistingDirs(List.of(withReal, empty),
            p -> p.getCompileSourceRoots());

        assertThat(roots).containsExactly(real.toAbsolutePath().normalize());
    }

    @Test
    void generatedSourceRoots_discoversExistingSubdirsOnDisk(@TempDir Path root) throws Exception {
        // target/generated-sources/{jooq,graphitron,annotations} all exist on disk; a stray
        // file under generated-sources is not a root; the result is the three dirs, sorted.
        Path target = root.resolve("target");
        Path jooq = Files.createDirectories(target.resolve("generated-sources/jooq"));
        Path graphitron = Files.createDirectories(target.resolve("generated-sources/graphitron"));
        Path annotations = Files.createDirectories(target.resolve("generated-sources/annotations"));
        Files.writeString(target.resolve("generated-sources/marker.txt"), "x");
        var project = projectWithBuild(target.toString(), null);

        var roots = AbstractRewriteMojo.generatedSourceRoots(project);

        assertThat(roots).containsExactlyInAnyOrder(
            annotations.toString(), graphitron.toString(), jooq.toString());
        assertThat(roots).doesNotContain(target.resolve("generated-sources/marker.txt").toString());
    }

    @Test
    void generatedSourceRoots_emptyWhenNoBuildOrNoGeneratedSourcesDir(@TempDir Path root) {
        // No build at all (hand-built unit-tier project) -> empty.
        assertThat(AbstractRewriteMojo.generatedSourceRoots(new MavenProject())).isEmpty();
        // A build directory with no generated-sources subdir -> empty.
        var project = projectWithBuild(root.resolve("target").toString(), null);
        assertThat(AbstractRewriteMojo.generatedSourceRoots(project)).isEmpty();
    }

    @Test
    void compileSourceRoots_includeGeneratedSourcesAbsentFromCompileSourceRoots(@TempDir Path root) throws Exception {
        // The core regression: a module whose getCompileSourceRoots() carries only
        // src/main/java but whose target/generated-sources/jooq exists on disk (the
        // sibling never built this session). The resolved walked roots must include
        // the jooq dir; pre-fix they would be just src/main/java.
        Path src = Files.createDirectories(root.resolve("src/main/java"));
        Path target = root.resolve("target");
        Path jooq = Files.createDirectories(target.resolve("generated-sources/jooq"));
        var project = projectWithBuild(target.toString(), null);
        project.addCompileSourceRoot(src.toString());

        var walked = AbstractRewriteMojo.collectExistingDirs(
            List.of(project), AbstractRewriteMojo::compileSourceRootsOf);

        assertThat(walked).contains(
            src.toAbsolutePath().normalize(), jooq.toAbsolutePath().normalize());
    }

    @Test
    void compileSourceRoots_dedupGeneratedRootAlreadyRegisteredByPlugin(@TempDir Path root) throws Exception {
        // Full-lifecycle goal: the plugin already appended target/generated-sources/jooq
        // to getCompileSourceRoots(). The disk discovery finds the same dir; collectExistingDirs
        // collapses them so it is walked exactly once (no double-walk).
        Path target = root.resolve("target");
        Path jooq = Files.createDirectories(target.resolve("generated-sources/jooq"));
        var project = projectWithBuild(target.toString(), null);
        project.addCompileSourceRoot(jooq.toString());

        var walked = AbstractRewriteMojo.collectExistingDirs(
            List.of(project), AbstractRewriteMojo::compileSourceRootsOf);

        assertThat(walked).containsExactly(jooq.toAbsolutePath().normalize());
    }

    @Test
    void unwalkedScannedModules_namesModuleScannedWithNoWalkedSourceRoot(@TempDir Path root) throws Exception {
        // bare: target/classes exists (scanned) but no source root at all -> reported.
        Path bareClasses = Files.createDirectories(root.resolve("bare/target/classes"));
        var bare = projectWithBuild(root.resolve("bare/target").toString(), bareClasses.toString());
        bare.setGroupId("test"); bare.setArtifactId("bare"); bare.setVersion("1.0");

        // walked: target/classes plus src/main/java -> not reported.
        Path walkedClasses = Files.createDirectories(root.resolve("walked/target/classes"));
        Files.createDirectories(root.resolve("walked/src/main/java"));
        var walked = projectWithBuild(root.resolve("walked/target").toString(), walkedClasses.toString());
        walked.addCompileSourceRoot(root.resolve("walked/src/main/java").toString());
        walked.setGroupId("test"); walked.setArtifactId("walked"); walked.setVersion("1.0");

        // generated-only: target/classes plus an on-disk generated-sources/jooq but no
        // hand-written source root -> NOT reported, because the auto-include covers it (R369).
        Path genClasses = Files.createDirectories(root.resolve("gen/target/classes"));
        Files.createDirectories(root.resolve("gen/target/generated-sources/jooq"));
        var gen = projectWithBuild(root.resolve("gen/target").toString(), genClasses.toString());
        gen.setGroupId("test"); gen.setArtifactId("gen"); gen.setVersion("1.0");

        var reported = AbstractRewriteMojo.unwalkedScannedModules(List.of(bare, walked, gen));

        assertThat(reported).containsExactly(bare.getId());
    }

    private static MavenProject projectWithBuild(String directory, String outputDirectory) {
        var project = new MavenProject();
        var build = new Build();
        build.setDirectory(directory);
        if (outputDirectory != null) {
            build.setOutputDirectory(outputDirectory);
        }
        project.setBuild(build);
        return project;
    }

    private static ValidationError error(String coordinate, String message, String file, int line, int col) {
        return new ValidationError(coordinate, Rejection.invalidSchema(message),
            new SourceLocation(line, col, file));
    }
}
