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

    private static ValidationError error(String coordinate, String message, String file, int line, int col) {
        return new ValidationError(coordinate, Rejection.invalidSchema(message),
            new SourceLocation(line, col, file));
    }
}
