package no.sikt.graphitron.rewrite.generators.fields;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
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
        assertThat(generateNames("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """)).contains("FilmFields");
    }

    @Test
    void rootType_producesFieldsClass() {
        assertThat(generateNames("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """)).contains("QueryFields");
    }

    @Test
    void classNameFollowsGraphQLTypeName() {
        // GraphQL type "MovieItem" maps to SQL table "film" → fields class is "MovieItemFields"
        var names = generateNames("""
            type MovieItem @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(names).contains("MovieItemFields");
        assertThat(names).doesNotContain("FilmFields");
    }

    @Test
    void recordType_notIncluded() {
        assertThat(generateNames("""
            type Container @record { value: String }
            type Query { dummy: String }
            """)).doesNotContain("ContainerFields");
    }

    @Test
    void generatedClass_containsFieldMethod() {
        var filmFields = findSpec("FilmFields", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(filmFields.methodSpecs()).extracting(MethodSpec::name).contains("title");
    }

    @Test
    void notGeneratedField_isExcluded() {
        var filmFields = findSpec("FilmFields", """
            type Film @table(name: "film") { title: String, hidden: String @notGenerated }
            type Query { dummy: String }
            """);
        assertThat(filmFields.methodSpecs()).extracting(MethodSpec::name).contains("title");
        assertThat(filmFields.methodSpecs()).extracting(MethodSpec::name).doesNotContain("hidden");
    }

    @Test
    void multipleTableTypes_eachProducesFieldsClass() {
        var names = generateNames("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(names).containsAll(List.of("FilmFields", "ActorFields", "QueryFields"));
    }

    // ===== @splitQuery fields =====

    @Test
    void splitQueryField_asyncDataFetcherIsInParentTypeFieldsClass() {
        var languageFields = findSpec("LanguageFields", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(languageFields.methodSpecs()).extracting(MethodSpec::name).contains("films");
    }

    @Test
    void splitQueryField_asyncDataFetcherReturnsCompletableFuture() {
        var languageFields = findSpec("LanguageFields", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        var films = languageFields.methodSpecs().stream()
            .filter(m -> m.name().equals("films")).findFirst().orElseThrow();
        assertThat(films.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void splitQueryField_rowsMethodIsInParentTypeFieldsClass() {
        var languageFields = findSpec("LanguageFields", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(languageFields.methodSpecs()).extracting(MethodSpec::name).contains("rowsFilms");
    }

    // ===== Helpers =====

    private List<String> generateNames(String sdl) {
        return FieldsClassGenerator.generate(buildSchema(sdl)).stream()
            .map(TypeSpec::name)
            .toList();
    }

    private TypeSpec findSpec(String className, String sdl) {
        return FieldsClassGenerator.generate(buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
