package no.sikt.graphitron.rewrite.generators.fields;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full fields class pipeline: SDL schema → {@link GraphitronSchema} →
 * generated class list.
 *
 * <p>Verifies that {@link FieldsClassGenerator} produces exactly one {@code *Fields} class per
 * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType} and
 * {@link no.sikt.graphitron.rewrite.type.GraphitronType.RootType}, named after the GraphQL type
 * (not the SQL table), and skips all other type categories.
 */
class FieldsPipelineTest {

    @BeforeEach
    void setup() {
        GeneratorConfig.setProperties(
            Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE,
            List.of(), Set.of(), List.of());
    }

    @AfterEach
    void teardown() {
        GeneratorConfig.clear();
    }

    @Test
    void tableType_producesFieldsClass() {
        var classes = generateNames("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFields");
    }

    @Test
    void rootType_producesFieldsClass() {
        var classes = generateNames("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("QueryFields");
    }

    @Test
    void classNameFollowsGraphQLTypeName() {
        // GraphQL type "MovieItem" maps to SQL table "film" → fields class is "MovieItemFields"
        var classes = generateNames("""
            type MovieItem @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("MovieItemFields");
        assertThat(classes).doesNotContain("FilmFields");
    }

    @Test
    void recordType_notIncluded() {
        var classes = generateNames("""
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(classes).doesNotContain("ContainerFields");
    }

    @Test
    void generatedClass_containsFieldMethod() {
        var sources = generateSources("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        var filmFields = sources.stream()
            .filter(s -> s.contains("class FilmFields"))
            .findFirst();
        assertThat(filmFields).isPresent();
        assertThat(filmFields.get()).contains("title(");
    }

    @Test
    void notGeneratedField_isExcluded() {
        var sources = generateSources("""
            type Film @table(name: "film") { title: String, hidden: String @notGenerated }
            type Query { dummy: String }
            """);
        var filmFields = sources.stream()
            .filter(s -> s.contains("class FilmFields"))
            .findFirst();
        assertThat(filmFields).isPresent();
        assertThat(filmFields.get()).contains("title(");
        assertThat(filmFields.get()).doesNotContain("hidden(");
    }

    @Test
    void multipleTableTypes_eachProducesFieldsClass() {
        var classes = generateNames("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(classes).containsAnyOf("FilmFields", "ActorFields");
        assertThat(classes).hasSize(3); // FilmFields, ActorFields, QueryFields
    }

    // ===== Helpers =====

    private List<String> generateNames(String sdl) {
        return new FieldsClassGenerator(buildSchema(sdl)).generateAll().stream()
            .map(TypeSpec::name)
            .toList();
    }

    private List<String> generateSources(String sdl) {
        return new FieldsClassGenerator(buildSchema(sdl)).generateAll().stream()
            .map(t -> JavaFile.builder("test.pkg", t).indent("    ").build().toString())
            .toList();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
