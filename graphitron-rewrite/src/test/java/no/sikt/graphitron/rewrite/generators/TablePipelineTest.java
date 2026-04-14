package no.sikt.graphitron.rewrite.generators;

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
 * <p>Verifies that {@link TypeClassGenerator} produces exactly one class per distinct SQL table
 * referenced by a {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType}, named after
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

    // ===== fields() method =====

    @Test
    void fieldsMethod_containsScalarColumnsFromSchema() {
        var filmSpec = findSpec("Film", """
            type Film @table(name: "film") { title: String, filmId: Int @field(name: "film_id") }
            type Query { dummy: String }
            """);
        var fields = filmSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("fields")).findFirst().orElseThrow();
        var code = fields.code().toString();
        assertThat(code).contains("case \"title\"");
        assertThat(code).contains("table.TITLE");
        assertThat(code).contains("case \"filmId\"");
        assertThat(code).contains("table.FILM_ID");
    }

    @Test
    void fieldsMethod_excludesNotGeneratedFields() {
        var filmSpec = findSpec("Film", """
            type Film @table(name: "film") { title: String, hidden: String @notGenerated }
            type Query { dummy: String }
            """);
        var fields = filmSpec.methodSpecs().stream()
            .filter(m -> m.name().equals("fields")).findFirst().orElseThrow();
        var code = fields.code().toString();
        assertThat(code).contains("case \"title\"");
        assertThat(code).doesNotContain("hidden");
    }

    // ===== subselectMany / subselectOne =====

    @Test
    void subselectMany_usesMultiset() {
        var code = findSubselectMethod("Film", "subselectMany", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """).code().toString();
        assertThat(code).contains("DSL.multiset(");
        assertThat(code).contains("fields(sel.getSelectionSet())");
        assertThat(code).contains(".from(");
        assertThat(code).contains(".where(condition)");
        assertThat(code).contains(".orderBy(orderBy)");
        assertThat(code).contains(".as(sel.getResultKey())");
        assertThat(code).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void subselectOne_usesMultisetWithLimit() {
        var code = findSubselectMethod("Film", "subselectOne", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """).code().toString();
        assertThat(code).contains("DSL.multiset(");
        assertThat(code).contains("fields(sel.getSelectionSet())");
        assertThat(code).contains(".from(");
        assertThat(code).contains(".where(condition)");
        assertThat(code).contains(".limit(1)");
        assertThat(code).contains(".as(sel.getResultKey())");
        assertThat(code).contains(".convertFrom(");
        assertThat(code).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void subselectMany_tableRefIsCorrectForSchema() {
        // Verify the table variable references the schema-bound jOOQ table (FILM), not a hardcoded one
        var code = findSubselectMethod("Film", "subselectMany", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """).code().toString();
        assertThat(code).contains("FILM");
    }

    // ===== Helpers =====

    private no.sikt.graphitron.javapoet.MethodSpec findSubselectMethod(
            String className, String methodName, String sdl) {
        return findSpec(className, sdl).methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
    }

    private no.sikt.graphitron.javapoet.TypeSpec findSpec(String className, String sdl) {
        return TypeClassGenerator.generate(buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private List<String> generate(String sdl) {
        return TypeClassGenerator.generate(buildSchema(sdl)).stream()
            .map(t -> t.name())
            .toList();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
