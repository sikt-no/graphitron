package no.sikt.graphitron.rewrite.generators;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.generators.GraphitronWiringClassGenerator;
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
 * <p>Verifies that {@link TypeFieldsGenerator} produces exactly one {@code *Fields} class per
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType} and
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.RootType}, named after the GraphQL type
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
    void columnField_readsFromSourceRecord() {
        var filmFields = findSpec("FilmFields", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        var title = filmFields.methodSpecs().stream()
            .filter(m -> m.name().equals("title")).findFirst().orElseThrow();
        assertThat(title.code().toString()).contains("env.getSource()");
        assertThat(title.code().toString()).contains(".TITLE");
        assertThat(title.code().toString()).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void columnField_withFieldDirective_usesRemappedColumn() {
        var filmFields = findSpec("FilmFields", """
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Query { dummy: String }
            """);
        var filmId = filmFields.methodSpecs().stream()
            .filter(m -> m.name().equals("filmId")).findFirst().orElseThrow();
        assertThat(filmId.code().toString()).contains(".FILM_ID");
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

    // ===== Root query fields (G4) =====

    @Test
    void queryTableField_list_delegatesToSelectMany() {
        var queryFields = findSpec("QueryFields", """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """);
        var films = queryFields.methodSpecs().stream()
            .filter(m -> m.name().equals("films")).findFirst().orElseThrow();
        assertThat(films.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(films.code().toString()).contains("selectMany");
        assertThat(films.code().toString()).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void queryTableField_single_delegatesToSelectOne() {
        var queryFields = findSpec("QueryFields", """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var film = queryFields.methodSpecs().stream()
            .filter(m -> m.name().equals("film")).findFirst().orElseThrow();
        assertThat(film.returnType().toString()).isEqualTo("org.jooq.Record");
        assertThat(film.code().toString()).contains("selectOne");
    }

    @Test
    void queryTableField_withArgument_generatesConditionsClass() {
        var schema = buildSchema("""
            type Film @table(name: "film") { title: String, film_id: Int }
            type Query { film(film_id: Int!): Film }
            """);
        var conditionsClasses = TypeConditionsGenerator.generate(schema);
        assertThat(conditionsClasses).extracting(TypeSpec::name).contains("FilmConditions");
        var filmConditions = conditionsClasses.stream()
            .filter(t -> t.name().equals("FilmConditions")).findFirst().orElseThrow();
        assertThat(filmConditions.methodSpecs()).extracting(MethodSpec::name)
            .contains("filmCondition");
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

    // ===== @service fields =====

    @Test
    void serviceField_dataFetcherReturnsCompletableFutureListRecord() {
        var languageFields = findSpec("LanguageFields", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films(filter: String): [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilms"},
                    contextArguments: ["tenantId"]
                )
            }
            """);
        var films = languageFields.methodSpecs().stream()
            .filter(m -> m.name().equals("films")).findFirst().orElseThrow();
        assertThat(films.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_rowsMethodIsNamedLoadPlusFieldName() {
        var languageFields = findSpec("LanguageFields", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films(filter: String): [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilms"},
                    contextArguments: ["tenantId"]
                )
            }
            """);
        assertThat(languageFields.methodSpecs()).extracting(MethodSpec::name).contains("loadFilms");
    }

    // ===== GraphitronWiring (I1) =====

    @Test
    void wiringClass_referencesAllFieldsClasses() {
        var schema = buildSchema("""
            type Film @table(name: "film") { title: String }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query { dummy: String }
            """);
        var fieldsClasses = TypeFieldsGenerator.generate(schema);
        var fieldsClassNames = fieldsClasses.stream().map(TypeSpec::name).toList();
        var wiring = GraphitronWiringClassGenerator.generate(fieldsClassNames);

        assertThat(wiring.name()).isEqualTo("GraphitronWiring");
        var build = wiring.methodSpecs().stream()
            .filter(m -> m.name().equals("build")).findFirst().orElseThrow();
        assertThat(build.returnType().toString())
            .isEqualTo("graphql.schema.idl.RuntimeWiring.Builder");
    }

    @Test
    void wiringClass_noTypes_stillGenerates() {
        var wiring = GraphitronWiringClassGenerator.generate(List.of());
        assertThat(wiring.name()).isEqualTo("GraphitronWiring");
        assertThat(wiring.methodSpecs()).extracting(MethodSpec::name).contains("build");
    }

    // ===== Helpers =====

    private List<String> generateNames(String sdl) {
        return TypeFieldsGenerator.generate(buildSchema(sdl)).stream()
            .map(TypeSpec::name)
            .toList();
    }

    private TypeSpec findSpec(String className, String sdl) {
        return TypeFieldsGenerator.generate(buildSchema(sdl)).stream()
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
