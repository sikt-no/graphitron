package no.sikt.graphitron.rewrite.generators.fields;

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
 * Integration tests for the full fields pipeline: SDL schema → {@link GraphitronSchema} →
 * generated {@link javax.lang.model.element.TypeElement} list.
 *
 * <p>Verifies that {@link FieldsClassGenerator} produces exactly one class per
 * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType} and skips all other types.
 */
class FieldsPipelineTest {

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
    void singleTableType_producesOneFieldsClass() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).hasSize(1);
        assertThat(classes.get(0)).isEqualTo("FilmFields");
    }

    @Test
    void multipleTableTypes_producesOneClassEach() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactlyInAnyOrder("FilmFields", "ActorFields");
    }

    @Test
    void nonTableType_notIncluded() {
        var classes = generate("""
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(classes).isEmpty();
    }

    @Test
    void rootType_notIncluded() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).doesNotContain("QueryFields");
    }

    // ===== Helpers =====

    private List<String> generate(String sdl) {
        var gen = new FieldsClassGenerator(buildSchema(sdl));
        return gen.generateAll().stream().map(t -> t.name()).toList();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
