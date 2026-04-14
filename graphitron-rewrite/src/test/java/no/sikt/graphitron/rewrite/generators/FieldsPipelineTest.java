package no.sikt.graphitron.rewrite.generators;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.generators.GraphitronWiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherClassGenerator;
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
 *
 * <p>Method-level behaviour (column wiring, query/lookup/split/service fetchers) is verified
 * against the corresponding {@code *Fetchers} class produced by {@link TypeFetcherClassGenerator}.
 */
class FieldsPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE);
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    // ===== *Fields class existence (TypeFieldsGenerator still produces an empty shell) =====

    @Test
    void tableType_producesFieldsClass() {
        assertThat(generateFieldsNames("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """)).contains("FilmFields");
    }

    @Test
    void rootType_producesFieldsClass() {
        assertThat(generateFieldsNames("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """)).contains("QueryFields");
    }

    @Test
    void classNameFollowsGraphQLTypeName() {
        var names = generateFieldsNames("""
            type MovieItem @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(names).contains("MovieItemFields");
        assertThat(names).doesNotContain("FilmFields");
    }

    @Test
    void recordType_notIncluded() {
        assertThat(generateFieldsNames("""
            type Container @record { value: String }
            type Query { dummy: String }
            """)).doesNotContain("ContainerFields");
    }

    @Test
    void fieldsClass_isAlwaysEmpty() {
        var filmFields = findFieldsSpec("FilmFields", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(filmFields.methodSpecs()).isEmpty();
    }

    @Test
    void multipleTableTypes_eachProducesFieldsClass() {
        var names = generateFieldsNames("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(names).containsAll(List.of("FilmFields", "ActorFields", "QueryFields"));
    }

    // ===== Column fields → wired via ColumnFetcher in *Fetchers =====

    @Test
    void columnField_isWiredViaColumnFetcher() {
        var wiring = wiringCode(findFetcherSpec("FilmFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """));
        assertThat(wiring).contains("ColumnFetcher");
        assertThat(wiring).contains("TITLE");
    }

    @Test
    void columnField_withFieldDirective_usesRemappedColumn() {
        var wiring = wiringCode(findFetcherSpec("FilmFetchers", """
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Query { dummy: String }
            """));
        assertThat(wiring).contains("FILM_ID");
    }

    @Test
    void notGeneratedField_isExcluded() {
        var wiring = wiringCode(findFetcherSpec("FilmFetchers", """
            type Film @table(name: "film") { title: String, hidden: String @notGenerated }
            type Query { dummy: String }
            """));
        assertThat(wiring).contains("\"title\"");
        assertThat(wiring).doesNotContain("\"hidden\"");
    }

    // ===== Root query table fields → methods in *Fetchers =====

    @Test
    void queryTableField_list_delegatesToSelectMany() {
        var films = method(findFetcherSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """), "films");
        assertThat(films.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(films.code().toString()).contains("selectMany");
        assertThat(films.code().toString()).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void queryTableField_single_delegatesToSelectOne() {
        var film = method(findFetcherSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """), "film");
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

    // ===== @splitQuery fields → *Fetchers =====

    @Test
    void splitQueryField_asyncDataFetcherIsInParentTypeFetchersClass() {
        var languageFetchers = findFetcherSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(languageFetchers.methodSpecs()).extracting(MethodSpec::name).contains("films");
    }

    @Test
    void splitQueryField_asyncDataFetcherReturnsCompletableFuture() {
        var languageFetchers = findFetcherSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(method(languageFetchers, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void splitQueryField_rowsMethodIsInParentTypeFetchersClass() {
        var languageFetchers = findFetcherSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(languageFetchers.methodSpecs()).extracting(MethodSpec::name).contains("rowsFilms");
    }

    // ===== @service fields → *Fetchers =====

    @Test
    void serviceField_dataFetcherReturnsCompletableFutureListRecord() {
        var languageFetchers = findFetcherSpec("LanguageFetchers", """
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
        assertThat(method(languageFetchers, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_rowsMethodIsNamedLoadPlusFieldName() {
        var languageFetchers = findFetcherSpec("LanguageFetchers", """
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
        assertThat(languageFetchers.methodSpecs()).extracting(MethodSpec::name).contains("loadFilms");
    }

    // ===== GraphitronWiring =====

    @Test
    void wiringClass_referencesAllFetcherClasses() {
        var schema = buildSchema("""
            type Film @table(name: "film") { title: String }
            type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
            type Query { dummy: String }
            """);
        var fetcherClassNames = TypeFetcherClassGenerator.generate(schema).stream()
            .map(TypeSpec::name).toList();
        var wiring = GraphitronWiringClassGenerator.generate(fetcherClassNames);

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

    private List<String> generateFieldsNames(String sdl) {
        return TypeFieldsGenerator.generate(buildSchema(sdl)).stream()
            .map(TypeSpec::name)
            .toList();
    }

    private TypeSpec findFieldsSpec(String className, String sdl) {
        return TypeFieldsGenerator.generate(buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Fields class not found: " + className));
    }

    private TypeSpec findFetcherSpec(String className, String sdl) {
        return TypeFetcherClassGenerator.generate(buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Fetcher class not found: " + className));
    }

    private MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    private String wiringCode(TypeSpec spec) {
        return method(spec, "wiring").code().toString();
    }

    private GraphitronSchema buildSchema(String schemaText) {
        String directives = SchemaReadingHelper.fileAsString(
            Paths.get("../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directives + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
