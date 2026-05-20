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
 * R160 — pipeline-tier coverage that
 * {@link CatalogBuilder#buildSnapshot(TypeDefinitionRegistry,
 *     no.sikt.graphitron.rewrite.GraphitronSchema, CompletionData)} populates
 * {@link LspSchemaSnapshot.Built#fieldClassificationsByCoord()} with the expected
 * {@link FieldClassification} variants and payloads for representative permits across the
 * {@code ChildField} / {@code QueryField} / {@code MutationField} / {@code InputField}
 * sealed families.
 *
 * <p>The structural coverage contract is enforced at compile time by the projector's
 * exhaustive switch: adding a new permit to any of the four sealed families fails the
 * switch to compile until the LSP-side projection mapping is added in the same commit.
 * These tests assert the per-payload content is faithful to the model leaf.
 */
@PipelineTier
class FieldClassificationProjectionTest {

    @Test
    void columnFieldProjectsTableAndColumnName() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var classification = snapshot.fieldClassificationsByCoord().get("Film.title");
        assertThat(classification).isInstanceOf(FieldClassification.Column.class);
        var column = (FieldClassification.Column) classification;
        assertThat(column.tableName()).isEqualToIgnoringCase("film");
        assertThat(column.columnName()).isEqualTo("title");
    }

    @Test
    void columnFieldHonoursExplicitFieldNameOverride() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { movieTitle: String @field(name: "title") }
            type Query { film: Film }
            """);
        var column = (FieldClassification.Column) snapshot.fieldClassificationsByCoord().get("Film.movieTitle");
        assertThat(column.columnName()).isEqualTo("title");
    }

    @Test
    void columnReferenceFieldProjectsJoinPathAndTerminalTable() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var ref = (FieldClassification.ColumnReference) snapshot.fieldClassificationsByCoord().get("Film.languageName");
        assertThat(ref.columnName()).isEqualTo("name");
        assertThat(ref.tableName()).isEqualToIgnoringCase("language");
        assertThat(ref.joinPath()).hasSize(1);
        assertThat(ref.joinPath().get(0).targetTableName()).isEqualToIgnoringCase("language");
        assertThat(ref.joinPath().get(0).fkName()).isEqualToIgnoringCase("film_language_id_fkey");
    }

    @Test
    void queryTableFieldProjectsTargetTable() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """);
        var query = (FieldClassification.QueryTable) snapshot.fieldClassificationsByCoord().get("Query.films");
        assertThat(query.tableName()).isEqualToIgnoringCase("film");
        assertThat(query.isLookup()).isFalse();
    }

    @Test
    void inputColumnFieldProjectsInputTable() {
        var snapshot = snapshotOf("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query {
              film(key: FilmKey!): Film
            }
            """);
        var input = (FieldClassification.Column) snapshot.fieldClassificationsByCoord().get("FilmKey.filmId");
        assertThat(input.tableName()).isEqualToIgnoringCase("film");
        assertThat(input.columnName()).isEqualTo("film_id");
    }

    @Test
    void unclassifiedFieldProjectsRejectionReason() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { doesNotExist: String }
            type Query { film: Film }
            """);
        var unclassified = (FieldClassification.Unclassified) snapshot.fieldClassificationsByCoord().get("Film.doesNotExist");
        assertThat(unclassified.reason()).isNotBlank();
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
