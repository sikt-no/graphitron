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
 * Integration tests for the full table class pipeline: SDL schema → {@link GraphitronSchema} →
 * generated class list.
 *
 * <p>Verifies that {@link TableClassGenerator} produces exactly one class per distinct SQL table
 * referenced by a {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType}, named after
 * the table (not the GraphQL type name), and skips all other types.
 */
class TablePipelineTest {

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
    void singleTableType_producesOneClass() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactly("Film");
    }

    @Test
    void multipleTableTypes_producesOneClassEach() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactlyInAnyOrder("Film", "Actor");
    }

    @Test
    void classNameFollowsTableNotTypeName() {
        // GraphQL type "MovieItem" maps to SQL table "film" → class should be "Film"
        var classes = generate("""
            type MovieItem @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactly("Film");
        assertThat(classes).doesNotContain("MovieItem");
    }

    @Test
    void snakeCaseTableName_convertedToPascalCase() {
        var classes = generate("""
            type FilmActor @table(name: "film_actor") { actorId: Int }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactly("FilmActor");
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
        assertThat(classes).doesNotContain("Query");
    }

    // ===== Helpers =====

    private List<String> generate(String sdl) {
        var gen = new TableClassGenerator(buildSchema(sdl));
        return gen.generateAll().stream().map(t -> t.name()).toList();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
