package no.sikt.graphitron.rewrite.maven;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.idl.errors.SchemaProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaProblemDiagnosticTest {

    @Test
    void format_listsLoadedFilesAndUnderlyingErrors(@TempDir Path basedir) throws Exception {
        var loaded = basedir.resolve("src/main/resources/types.graphqls");
        Files.createDirectories(loaded.getParent());
        Files.createFile(loaded);

        var msg = SchemaProblemDiagnostic.format(
            problem("A schema MUST have a 'query' operation defined"),
            List.of(loaded.toString()),
            basedir);

        assertThat(msg)
            .contains("GraphQL schema validation failed:")
            .contains("- A schema MUST have a 'query' operation defined")
            .contains("Schema files loaded by Graphitron (1)")
            .contains("src/main/resources/types.graphqls");
    }

    @Test
    void format_missingQueryError_emitsQueryHint(@TempDir Path basedir) {
        var msg = SchemaProblemDiagnostic.format(
            problem("A schema MUST have a 'query' operation defined"),
            List.of(),
            basedir);

        assertThat(msg).contains("Hint: declare a 'type Query");
    }

    @Test
    void format_undeclaredDirectiveError_emitsFederationHint(@TempDir Path basedir) {
        var msg = SchemaProblemDiagnostic.format(
            problem("'Foo' [@10:1] tried to use an undeclared directive 'key'"),
            List.of(),
            basedir);

        assertThat(msg)
            .contains("Hint: graphql-java does not bundle Apollo Federation directives.")
            .doesNotContain("type Query");
    }

    @Test
    void format_unknownErrorKind_omitsHintSection(@TempDir Path basedir) {
        var msg = SchemaProblemDiagnostic.format(
            problem("Some other graphql-java schema error"),
            List.of(),
            basedir);

        assertThat(msg).doesNotContain("Hint:");
    }

    @Test
    void format_includesOrphansFoundUnderSrcMain(@TempDir Path basedir) throws Exception {
        var loaded = basedir.resolve("src/main/resources/types.graphqls");
        var orphan = basedir.resolve("src/main/resources/queries.graphqls");
        Files.createDirectories(loaded.getParent());
        Files.createFile(loaded);
        Files.createFile(orphan);

        var msg = SchemaProblemDiagnostic.format(
            problem("A schema MUST have a 'query' operation defined"),
            List.of(loaded.toString()),
            basedir);

        assertThat(msg)
            .contains("Found under src" + java.io.File.separator + "main"
                + " but not declared in <schemaInputs> (1)")
            .contains("queries.graphqls");
    }

    @Test
    void format_emptyLoadedSet_reportsExplicitNone(@TempDir Path basedir) {
        var msg = SchemaProblemDiagnostic.format(
            problem("A schema MUST have a 'query' operation defined"),
            List.of(),
            basedir);

        assertThat(msg)
            .contains("Schema files loaded by Graphitron (0)")
            .contains("(none");
    }

    @Test
    void findOrphanSchemaFiles_skipsTargetDirectory(@TempDir Path basedir) throws Exception {
        var srcMain = basedir.resolve("src/main");
        Files.createDirectories(srcMain);
        Files.createFile(srcMain.resolve("a.graphqls"));

        var generated = basedir.resolve("target/generated-sources/graphitron");
        Files.createDirectories(generated);
        Files.createFile(generated.resolve("b.graphqls"));

        var orphans = SchemaProblemDiagnostic.findOrphanSchemaFiles(java.util.Set.of(), basedir);

        assertThat(orphans).extracting(Path::toString)
            .anyMatch(s -> s.endsWith("a.graphqls"))
            .noneMatch(s -> s.contains("target"));
    }

    @Test
    void findOrphanSchemaFiles_acceptsBothExtensions(@TempDir Path basedir) throws Exception {
        var srcMain = basedir.resolve("src/main");
        Files.createDirectories(srcMain);
        Files.createFile(srcMain.resolve("short.graphql"));
        Files.createFile(srcMain.resolve("long.graphqls"));
        Files.createFile(srcMain.resolve("README.md"));

        var orphans = SchemaProblemDiagnostic.findOrphanSchemaFiles(java.util.Set.of(), basedir);

        assertThat(orphans).extracting(p -> p.getFileName().toString())
            .containsExactlyInAnyOrder("short.graphql", "long.graphqls");
    }

    @Test
    void findOrphanSchemaFiles_excludesAlreadyLoadedAbsolutePaths(@TempDir Path basedir) throws Exception {
        var srcMain = basedir.resolve("src/main");
        Files.createDirectories(srcMain);
        var loaded = srcMain.resolve("loaded.graphqls");
        var notLoaded = srcMain.resolve("not-loaded.graphqls");
        Files.createFile(loaded);
        Files.createFile(notLoaded);

        var orphans = SchemaProblemDiagnostic.findOrphanSchemaFiles(
            java.util.Set.of(loaded.toAbsolutePath().normalize()), basedir);

        assertThat(orphans).extracting(p -> p.getFileName().toString())
            .containsExactly("not-loaded.graphqls");
    }

    @Test
    void findOrphanSchemaFiles_missingSrcMain_returnsEmpty(@TempDir Path basedir) {
        var orphans = SchemaProblemDiagnostic.findOrphanSchemaFiles(java.util.Set.of(), basedir);
        assertThat(orphans).isEmpty();
    }

    private static SchemaProblem problem(String... messages) {
        var errors = new java.util.ArrayList<GraphQLError>();
        for (String m : messages) {
            errors.add(GraphqlErrorBuilder.newError().message(m).build());
        }
        return new SchemaProblem(errors);
    }
}
