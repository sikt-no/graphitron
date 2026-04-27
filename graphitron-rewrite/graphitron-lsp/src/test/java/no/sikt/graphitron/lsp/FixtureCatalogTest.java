package no.sikt.graphitron.lsp;

import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TreeSitterGraphql;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LSP diagnostics and completions verified against the real fixture jOOQ catalog.
 * These tests complement the hand-crafted catalog tests in {@link DiagnosticsTest},
 * {@link FieldCompletionsTest}, and {@link TableCompletionsTest} by proving that
 * {@link CatalogBuilder} wires up correctly and that the catalog accurately reflects
 * the generated jOOQ metadata (column names, FK references, nullability).
 */
class FixtureCatalogTest {

    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    private static CompletionData catalog() {
        var jooq = new JooqCatalog(JOOQ_PACKAGE);
        GraphQLSchema schema = GraphQLSchema.newSchema()
            .query(GraphQLObjectType.newObject()
                .name("Query")
                .field(GraphQLFieldDefinition.newFieldDefinition()
                    .name("x").type(Scalars.GraphQLInt).build())
                .build())
            .build();
        var ctx = new RewriteContext(
            List.of(), Path.of(""), Path.of(""), "fake.output", JOOQ_PACKAGE, Map.of()
        );
        return CatalogBuilder.build(jooq, schema, ctx);
    }

    // ---- Table presence ----

    @Test
    void catalogContainsFixtureTables() {
        assertThat(catalog().tables()).extracting(CompletionData.Table::name)
            .contains("film", "actor", "language");
    }

    // ---- Column names: Java field names in CompletionData ----

    @Test
    void filmColumnsUseJavaFieldNames() {
        var film = catalog().getTable("film").orElseThrow();
        assertThat(film.columns()).extracting(CompletionData.Column::name)
            .contains("FILM_ID", "TITLE", "LANGUAGE_ID");
        assertThat(film.columns()).extracting(CompletionData.Column::name)
            .doesNotContain("film_id", "title");
    }

    // ---- Table completions ----

    @Test
    void tableCompletionsReturnSqlTableNames() {
        String source = "type Foo @table(name: \"\") { x: Int }";
        TSPoint cursor = new TSPoint(0, source.indexOf('"') + 1);
        var items = tableCompletions(catalog(), source, cursor);
        assertThat(items).extracting(CompletionItem::getLabel)
            .contains("film", "actor", "language");
    }

    // ---- Field completions: Java field names ----

    @Test
    void fieldCompletionsReturnJavaFieldNames() {
        String source = """
            type Foo @table(name: "film") {
                x: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        var items = fieldCompletions(catalog(), source, new TSPoint(line, col));
        assertThat(items).extracting(CompletionItem::getLabel)
            .contains("FILM_ID", "TITLE", "LANGUAGE_ID");
        assertThat(items).extracting(CompletionItem::getLabel)
            .doesNotContain("film_id", "title");
    }

    // ---- Diagnostics: column resolution ----

    @Test
    void javaFieldNameProducesNoDiagnostic() {
        var file = new WorkspaceFile(1, """
            type Foo @table(name: "film") {
                x: Int @field(name: "FILM_ID")
            }
            """);
        assertThat(Diagnostics.compute(file, catalog())).isEmpty();
    }

    @Test
    void sqlColumnNameAlsoAccepted() {
        // SQL names resolve because Diagnostics.validateField uses equalsIgnoreCase on
        // Column.name() (the Java field name); FILM_ID.equalsIgnoreCase(film_id) = true.
        var file = new WorkspaceFile(1, """
            type Foo @table(name: "film") {
                x: Int @field(name: "film_id")
            }
            """);
        assertThat(Diagnostics.compute(file, catalog())).isEmpty();
    }

    @Test
    void unknownColumnProducesDiagnostic() {
        var file = new WorkspaceFile(1, """
            type Foo @table(name: "film") {
                x: Int @field(name: "NO_SUCH_COL")
            }
            """);
        var diags = Diagnostics.compute(file, catalog());
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("NO_SUCH_COL");
    }

    // ---- Diagnostics: FK reference ----

    @Test
    void knownFkKeyFromCatalogProducesNoDiagnostic() {
        // Read the FK key name from the catalog itself so the test does not
        // hard-code a constant that may change under different jOOQ naming strategies.
        var data = catalog();
        String fkKey = data.getTable("film").orElseThrow().references().stream()
            .filter(r -> !r.inverse())
            .map(CompletionData.Reference::keyName)
            .findFirst().orElseThrow();
        var file = new WorkspaceFile(1, String.format("""
            type Foo @table(name: "film") {
                x: Int @reference(path: [{key: "%s"}])
            }
            """, fkKey));
        assertThat(Diagnostics.compute(file, data)).isEmpty();
    }

    // ---- Helpers ----

    private static List<CompletionItem> tableCompletions(CompletionData data, String source, TSPoint cursor) {
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser().parseString(null, source);
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("no directive at cursor"));
        return TableCompletions.generate(data, directive, cursor, bytes);
    }

    private static List<CompletionItem> fieldCompletions(CompletionData data, String source, TSPoint cursor) {
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser().parseString(null, source);
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("no directive at cursor"));
        return FieldCompletions.generate(data, directive, cursor, bytes);
    }

    private static TSParser parser() {
        var p = new TSParser();
        p.setLanguage(new TreeSitterGraphql());
        return p;
    }
}
