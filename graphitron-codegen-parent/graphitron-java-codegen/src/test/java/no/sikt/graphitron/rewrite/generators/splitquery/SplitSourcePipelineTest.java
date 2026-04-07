package no.sikt.graphitron.rewrite.generators.splitquery;

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
 * Integration tests for the full split-source pipeline: SDL schema → {@link GraphitronSchema} →
 * {@link SplitSourceSpec} list.
 *
 * <p>Verifies that {@link SplitSourceSpecBuilder} correctly reads the schema — parent type name,
 * field name, parent table Java field name, and key column mappings.
 * Code-generation correctness is covered by {@link SplitSourceCodeGeneratorTest}; runtime
 * behaviour of the generated code is covered by the execution tests in
 * {@code graphitron-rewrite-test-spec}.
 */
class SplitSourcePipelineTest {

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
    void splitQueryField_buildsOneSpec() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs).hasSize(1);
    }

    @Test
    void splitQueryField_parentTypeNameIsCorrect() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs.get(0).parentTypeName()).isEqualTo("Language");
    }

    @Test
    void splitQueryField_fieldNameIsCorrect() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs.get(0).fieldName()).isEqualTo("films");
    }

    @Test
    void splitQueryField_parentTableJavaFieldNameIsCorrect() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs.get(0).parentTableJavaFieldName()).isEqualTo("LANGUAGE");
    }

    @Test
    void splitQueryField_keyFieldResolvesCorrectJavaName() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs.get(0).keyFields()).hasSize(1);
        assertThat(specs.get(0).keyFields().get(0).columnJavaName()).isEqualTo("LANGUAGE_ID");
    }

    @Test
    void splitQueryField_keyFieldColumnClassIsCorrect() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        // serial / int4 maps to java.lang.Long in the test DB's jOOQ mapping
        assertThat(specs.get(0).keyFields().get(0).columnClass()).isEqualTo("java.lang.Long");
    }

    @Test
    void nonSplitQueryTableField_notIncluded() {
        var specs = buildSpecs("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs).isEmpty();
    }

    @Test
    void splitQueryOnNonTableParent_notIncluded() {
        // ResultType parent has no table → spec dropped
        var specs = buildSpecs("""
            type Container @record { }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Container {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(specs).isEmpty();
    }

    // ===== Helpers =====

    private List<SplitSourceSpec> buildSpecs(String sdl) {
        return SplitSourceSpecBuilder.build(buildSchema(sdl));
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directivesContent = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesContent + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
