package no.sikt.graphitron.rewrite.catalog;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R233 — pipeline-tier coverage of the producer-side
 * {@link FieldClassification#lspColumnDispatch()} switch. Drives the full classifier on a small
 * synthetic schema covering the three audience-specific arms (Resolve / Silent / FallThrough)
 * across representative permits. The exhaustive switch is the load-bearing meta-assertion: a new
 * permit on {@link FieldClassification} fails {@code lspColumnDispatch()} to compile, forcing the
 * implementer to place it in one of the three arms deliberately in one place, ahead of any
 * consumer-side switch.
 */
@PipelineTier
class LspColumnDispatchProjectionTest {

    @Test
    void columnFieldDispatchesResolveOnEnclosingTable() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var classification = snapshot.fieldClassificationsByCoord().get("Film.title");
        var dispatch = classification.lspColumnDispatch();
        assertThat(dispatch).isInstanceOf(FieldClassification.LspColumnDispatch.Resolve.class);
        assertThat(((FieldClassification.LspColumnDispatch.Resolve) dispatch).tableName())
            .isEqualToIgnoringCase("film");
    }

    @Test
    void columnReferenceFieldDispatchesResolveOnTerminalTable() {
        // The load-bearing case for R233 / R224: @reference path field dispatches to the terminal
        // table, not the enclosing type's @table.
        var snapshot = snapshotOf("""
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var classification = snapshot.fieldClassificationsByCoord().get("Film.languageName");
        var dispatch = classification.lspColumnDispatch();
        assertThat(dispatch).isInstanceOf(FieldClassification.LspColumnDispatch.Resolve.class);
        assertThat(((FieldClassification.LspColumnDispatch.Resolve) dispatch).tableName())
            .isEqualToIgnoringCase("language");
    }

    @Test
    void inputColumnFieldDispatchesResolveOnInputTable() {
        var snapshot = snapshotOf("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { film(key: FilmKey!): Film }
            """);
        var classification = snapshot.fieldClassificationsByCoord().get("FilmKey.filmId");
        var dispatch = classification.lspColumnDispatch();
        assertThat(dispatch).isInstanceOf(FieldClassification.LspColumnDispatch.Resolve.class);
        assertThat(((FieldClassification.LspColumnDispatch.Resolve) dispatch).tableName())
            .isEqualToIgnoringCase("film");
    }

    @Test
    void unclassifiedFieldDispatchesSilent() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { doesNotExist: String }
            type Query { film: Film }
            """);
        var classification = snapshot.fieldClassificationsByCoord().get("Film.doesNotExist");
        assertThat(classification.lspColumnDispatch())
            .isInstanceOf(FieldClassification.LspColumnDispatch.Silent.class);
    }

    @Test
    void queryTableFieldDispatchesFallThrough() {
        // QueryTable is non-column-bearing on the @field(name:) axis; the LSP arm falls back to
        // its existing backing-driven dispatch.
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """);
        var classification = snapshot.fieldClassificationsByCoord().get("Query.films");
        assertThat(classification.lspColumnDispatch())
            .isInstanceOf(FieldClassification.LspColumnDispatch.FallThrough.class);
    }

    @Test
    void resolveCarriesTheSameTableAsTheClassificationPayload() {
        // Cross-check that Resolve's table is sourced from the classification's projected
        // tableName(), not from any per-arm side computation. The four column-bearing permits
        // (Column / ColumnReference / CompositeColumn / CompositeColumnReference) all read off
        // their own tableName() field; this asserts that the cross-permit invariant holds for
        // the ColumnReference arm, which is the load-bearing one for R224 / R233.
        var snapshot = snapshotOf("""
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var classification = (FieldClassification.ColumnReference)
            snapshot.fieldClassificationsByCoord().get("Film.languageName");
        var resolve = (FieldClassification.LspColumnDispatch.Resolve) classification.lspColumnDispatch();
        assertThat(resolve.tableName()).isEqualTo(classification.tableName());
    }

    private static LspSchemaSnapshot.Built snapshotOf(String schemaText) {
        var ctx = TestConfiguration.testContext();
        var prelude = directivesPrelude()
            + (schemaText.contains("interface Node") ? "" : "interface Node { id: ID! }\n");
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude + schemaText);
        var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
        var jooq = new JooqCatalog(ctx.jooqPackage());
        var catalog = CatalogBuilder.build(jooq, bundle.assembled(), ctx);
        return CatalogBuilder.buildSnapshot(registry, bundle.model(), catalog);
    }

    private static String directivesPrelude() {
        try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not load directives", e);
        }
    }
}
