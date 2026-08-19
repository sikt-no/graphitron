package no.sikt.graphitron.lsp;

import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LSP diagnostics verified against the real fixture jOOQ catalog, on both sides of the capture the
 * catalog now passes through. {@link CatalogBuilder}'s own projection is asserted directly, because
 * capture takes it as input and a wrong column name there is a wrong {@code sql_column} row; the
 * diagnostics then read the store the capture wrote, which is how a consumer's editor reaches the
 * same generated model.
 *
 * <p>It used to drive the table and column completion arms too. Those read the fact store now, and
 * {@link TableCompletionsTest} and {@link FieldCompletionsTest} capture the same generated model
 * into it, so the real-catalog coverage moved with them rather than being dropped.
 */
class FixtureCatalogTest {

    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    /**
     * The captured schema. {@code Foo}'s binding is a captured fact the column arm resolves through,
     * so the type the buffers below name is the type the store knows.
     */
    private static final String CAPTURED_SDL = """
        type Query { placeholder: Int }
        type Foo @table(name: "film") { x: Int }
        """;

    @TempDir
    static Path storeRoot;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(storeRoot, CAPTURED_SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

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
            List.of(), Path.of(""), "FixtureCatalogTest", Path.of(""), "fake.output", JOOQ_PACKAGE
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

    // ---- Diagnostics: column resolution ----

    @Test
    void javaFieldNameProducesNoDiagnostic() {
        var file = WorkspaceFileTestSupport.snapshot("""
            type Foo @table(name: "film") {
                x: Int @field(name: "FILM_ID")
            }
            """);
        assertThat(diagnose(file)).isEmpty();
    }

    @Test
    void sqlColumnNameProducesNoDiagnostic() {
        var file = WorkspaceFileTestSupport.snapshot("""
            type Foo @table(name: "film") {
                x: Int @field(name: "film_id")
            }
            """);
        assertThat(diagnose(file)).isEmpty();
    }

    @Test
    void unknownColumnProducesDiagnostic() {
        var file = WorkspaceFileTestSupport.snapshot("""
            type Foo @table(name: "film") {
                x: Int @field(name: "NO_SUCH_COL")
            }
            """);
        var diags = diagnose(file);
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("NO_SUCH_COL");
    }

    // ---- Diagnostics: FK reference ----

    @Test
    void knownFkKeyFromCatalogProducesNoDiagnostic() {
        // Read the FK key name from the catalog itself so the test does not
        // hard-code a constant that may change under different jOOQ naming strategies.
        String fkKey = catalog().getTable("film").orElseThrow().references().stream()
            .filter(r -> !r.inverse())
            .map(CompletionData.Reference::keyName)
            .findFirst().orElseThrow();
        var file = WorkspaceFileTestSupport.snapshot(String.format("""
            type Foo @table(name: "film") {
                x: Int @reference(path: [{key: "%s"}])
            }
            """, fkKey));
        assertThat(diagnose(file)).isEmpty();
    }


    private static List<Diagnostic> diagnose(no.sikt.graphitron.lsp.state.FileSnapshot file) {
        return Diagnostics.compute(LspVocabulary.load(), "", file, Optional.of(store.handle()));
    }
}
