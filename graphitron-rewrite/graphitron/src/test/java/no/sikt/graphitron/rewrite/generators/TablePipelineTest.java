package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Integration tests for the full table class pipeline: SDL schema → {@link GraphitronSchema} →
 * generated class list.
 *
 * <p>Verifies that {@link TypeClassGenerator} produces exactly one class per distinct SQL table
 * referenced by a {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType}, named after
 * the table (not the GraphQL type name), and skips all other types.
 */
@PipelineTier
class TablePipelineTest {

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
    void classNameFollowsGraphQLTypeName() {
        // GraphQL type "MovieItem" maps to SQL table "film" → class is "MovieItem"
        var classes = generate("""
            type MovieItem @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactly("MovieItem");
        assertThat(classes).doesNotContain("Film");
    }

    @Test
    void twoTypesOnSameTable_producesTwoClasses() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type MovieItem @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsExactlyInAnyOrder("Film", "MovieItem");
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

    // ===== $fields() method =====

    @Test
    void fieldsMethod_containsScalarColumnsFromSchema() {
        var filmSpec = findSpec("Film", """
            type Film @table(name: "film") { title: String, filmId: Int @field(name: "film_id") }
            type Query { dummy: String }
            """);
        // Which SQL columns back which GraphQL fields is a $fields body-content question;
        // compile tier (graphitron-sakila-example) catches a wrong Tables.FILM.TITLE reference,
        // execution tier catches wrong values. Here we only verify the arms are present for each
        // declared GraphQL field.
        assertThat(TypeSpecAssertions.hasFieldsArm(filmSpec, "title")).isTrue();
        assertThat(TypeSpecAssertions.hasFieldsArm(filmSpec, "filmId")).isTrue();
    }

    // ===== Helpers =====

    private no.sikt.graphitron.javapoet.TypeSpec findSpec(String className, String sdl) {
        return TypeClassGenerator.generate(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private List<String> generate(String sdl) {
        return TypeClassGenerator.generate(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .map(t -> t.name())
            .toList();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
