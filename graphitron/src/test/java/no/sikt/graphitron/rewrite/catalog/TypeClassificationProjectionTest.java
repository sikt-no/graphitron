package no.sikt.graphitron.rewrite.catalog;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage that
 * {@link CatalogBuilder#buildSnapshot(TypeDefinitionRegistry,
 *     no.sikt.graphitron.rewrite.GraphitronSchema, CompletionData)} populates
 * {@link LspSchemaSnapshot.Built#typeClassificationsByName()} with the expected
 * {@link TypeClassification} variants and payloads.
 *
 * <p>The structural coverage contract is enforced at compile time by the projector's
 * exhaustive switch over {@code GraphitronType} permits; these tests assert per-payload
 * faithfulness.
 */
@PipelineTier
class TypeClassificationProjectionTest {

    @Test
    void tableTypeProjectsTableName() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var table = (TypeClassification.Table) snapshot.typeClassificationsByName().get("Film");
        assertThat(table.tableName()).isEqualToIgnoringCase("film");
    }

    @Test
    void plainInputProjectsPojoInputWithConsumerResolvedTables() {
        // Inputs carry no @table of their own; a formerly-@table key input is now a plain
        // input that projects as PojoInput. No producer binds a backing class (fqClassName is
        // null), and the tables its fields resolve against are read off the consuming fields'
        // classified targets (here Query.film's table).
        var snapshot = snapshotOf("""
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query {
              film(key: FilmKey!): Film
            }
            """);
        var input = (TypeClassification.PojoInput) snapshot.typeClassificationsByName().get("FilmKey");
        assertThat(input.fqClassName()).isNull();
        assertThat(input.resolvedTables()).hasSize(1);
        assertThat(input.resolvedTables().getFirst()).isEqualToIgnoringCase("film");
    }

    @Test
    void tableDirectiveOnInputProjectsThePlainInputClassification() {
        // `@table` on an input type is deprecated and ignored, so the projection carries the same
        // classification it would without the directive: no Unclassified arm, no migration reason
        // in the snapshot. The deprecation is announced as a build warning instead, which the
        // catalog projection does not carry.
        var snapshot = snapshotOf("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query {
              film(key: FilmKey!): Film
            }
            """);
        assertThat(snapshot.typeClassificationsByName().get("FilmKey"))
            .isNotInstanceOf(TypeClassification.Unclassified.class);
    }

    @Test
    void rootTypeProjectsOperationName() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var root = (TypeClassification.Root) snapshot.typeClassificationsByName().get("Query");
        assertThat(root.operation()).isEqualTo("Query");
    }

    @Test
    void scalarTypeProjectsJavaType() {
        var snapshot = snapshotOf("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var stringScalar = (TypeClassification.Scalar) snapshot.typeClassificationsByName().get("String");
        assertThat(stringScalar.javaType()).contains("String");
    }

    @Test
    void unclassifiedTypeProjectsRejectionReason() {
        var snapshot = snapshotOf("""
            type NoSuchTable @table(name: "no_such_table") { title: String }
            type Query { x: NoSuchTable }
            """);
        var unclassified = (TypeClassification.Unclassified) snapshot.typeClassificationsByName().get("NoSuchTable");
        assertThat(unclassified.reason()).isNotBlank();
    }

    private static LspSchemaSnapshot.Built snapshotOf(String schemaText) {
        var ctx = TestConfiguration.testContext();
        var prelude = directivesPrelude()
            + (schemaText.contains("interface Node") ? "" : "interface Node { id: ID! }\n");
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude + schemaText);
        var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
        return CatalogBuilder.buildSnapshot(registry, bundle.model());
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
