package no.sikt.graphitron.rewrite.generators.lookup;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full lookup pipeline: SDL schema → {@link GraphitronSchema} →
 * {@link LookupSpec} list.
 *
 * <p>Verifies that {@link LookupSpecBuilder} correctly reads the schema — field names, column
 * mappings, jOOQ types, and list cardinality — for each argument style. Code-generation
 * correctness is covered by {@link LookupCodeGeneratorTest}; runtime behaviour of the generated
 * code is covered by the execution tests in {@code graphitron-rewrite-test-spec}.
 */
class LookupPipelineTest {

    @BeforeEach
    void setup() {
        GeneratorConfig.setProperties(
            java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE,
            java.util.List.of(), java.util.Set.of(), java.util.List.of());
    }

    @AfterEach
    void teardown() {
        GeneratorConfig.clear();
    }

    @Test
    void flatScalarArg_buildsOneSpec() {
        var specs = buildSpecs("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).typeName()).isEqualTo("Film");
        assertThat(specs.get(0).inputArgName()).isNull();
        assertThat(specs.get(0).fields()).hasSize(1);
        var field = specs.get(0).fields().get(0);
        assertThat(field.argName()).isEqualTo("film_id");
        assertThat(field.columnJavaName()).isEqualTo("FILM_ID");
        assertThat(field.columnClass()).isEqualTo("java.lang.Long");
        assertThat(field.list()).isTrue();
    }

    @Test
    void flatMultipleArgs_listArgAndScalarArg() {
        var specs = buildSpecs("""
            type Customer @table(name: "customer") { firstName: String }
            type Query { customerById(customer_id: [ID] @lookupKey, store_id: ID @lookupKey): [Customer!]! }
            """);
        assertThat(specs).hasSize(1);
        var fields = specs.get(0).fields();
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).argName()).isEqualTo("customer_id");
        assertThat(fields.get(0).columnClass()).isEqualTo("java.lang.Long");
        assertThat(fields.get(0).list()).isTrue();
        assertThat(fields.get(1).argName()).isEqualTo("store_id");
        assertThat(fields.get(1).columnClass()).isEqualTo("java.lang.Long");
        assertThat(fields.get(1).list()).isFalse();
    }

    @Test
    void explicitTableInputTypeArg_buildsSpecWithInputArgName() {
        var specs = buildSpecs("""
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).inputArgName()).isEqualTo("key");
        assertThat(specs.get(0).fields()).hasSize(1);
        var field = specs.get(0).fields().get(0);
        assertThat(field.argName()).isEqualTo("filmId");
        assertThat(field.columnJavaName()).isEqualTo("FILM_ID");
        assertThat(field.columnClass()).isEqualTo("java.lang.Long");
    }

    @Test
    void implicitTableInputTypeArg_promotedAndBuildsSpec() {
        var specs = buildSpecs("""
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """);
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).inputArgName()).isEqualTo("key");
    }

    @Test
    void unresolvedScalarArg_droppedFromSpec() {
        // unknownColumn has no matching DB column → UnboundScalarArg → no spec fields → spec dropped
        var specs = buildSpecs("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(unknownColumn: [String] @lookupKey): [Film!]! }
            """);
        assertThat(specs).isEmpty();
    }

    // ===== Helpers =====

    private List<LookupSpec> buildSpecs(String sdl) {
        return LookupSpecBuilder.build(buildSchema(sdl));
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directivesContent = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesContent + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
