package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full fetcher class pipeline: SDL schema → {@link GraphitronSchema} →
 * generated class list.
 *
 * <p>Verifies that {@link TypeFetcherGenerator} produces exactly one {@code *Fetchers} class
 * per GraphQL type that is a {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType},
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}, or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.RootType}.
 */
class FetcherPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE);
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    @Test
    void singleTableType_producesOneFetchersClass() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFetchers");
    }

    @Test
    void multipleTableTypes_producesOneFetchersClassEach() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFetchers", "ActorFetchers");
    }

    @Test
    void classNameIsTypeNamePlusFetchers() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("FilmFetchers");
        assertThat(classes).doesNotContain("Film");
    }

    @Test
    void rootType_producesAFetchersClass() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("QueryFetchers");
    }

    @Test
    void nonTableType_notIncluded() {
        var classes = generate("""
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(classes).doesNotContain("ContainerFetchers");
    }

    // ===== Column fields → wired via ColumnFetcher =====

    @Test
    void wiring_columnField_usesColumnFetcher() {
        var wiringCode = findWiring("FilmFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(wiringCode).contains("ColumnFetcher");
        assertThat(wiringCode).doesNotContain("FilmFetchers::title");
    }

    @Test
    void columnField_withFieldDirective_usesRemappedColumn() {
        var wiring = findWiring("FilmFetchers", """
            type Film @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Query { dummy: String }
            """);
        assertThat(wiring).contains("FILM_ID");
    }

    @Test
    void notGeneratedField_isExcluded() {
        var wiring = findWiring("FilmFetchers", """
            type Film @table(name: "film") { title: String, hidden: String @notGenerated }
            type Query { dummy: String }
            """);
        assertThat(wiring).contains("\"title\"");
        assertThat(wiring).doesNotContain("\"hidden\"");
    }

    // ===== Root query table fields =====

    @Test
    void queryTableField_list_delegatesToSelectMany() {
        var films = method(findSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """), "films");
        assertThat(films.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(films.code().toString()).contains("selectMany");
        assertThat(films.code().toString()).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void queryTableField_single_delegatesToSelectOne() {
        var film = method(findSpec("QueryFetchers", """
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

    // ===== @splitQuery fields =====

    @Test
    void splitQueryField_asyncDataFetcherIsInParentTypeFetchersClass() {
        var languageFetchers = findSpec("LanguageFetchers", """
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
        var languageFetchers = findSpec("LanguageFetchers", """
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
        var languageFetchers = findSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(languageFetchers.methodSpecs()).extracting(MethodSpec::name).contains("rowsFilms");
    }

    // ===== @service fields =====

    @Test
    void serviceField_dataFetcherReturnsCompletableFutureListRecord() {
        var languageFetchers = findSpec("LanguageFetchers", """
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
        var languageFetchers = findSpec("LanguageFetchers", """
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
        var fetcherClassNames = TypeFetcherGenerator.generate(schema).stream()
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

    private List<String> generate(String sdl) {
        return TypeFetcherGenerator.generate(buildSchema(sdl)).stream()
            .map(TypeSpec::name)
            .toList();
    }

    private TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherGenerator.generate(buildSchema(sdl)).stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private String findWiring(String className, String sdl) {
        return findSpec(className, sdl).methodSpecs().stream()
            .filter(m -> m.name().equals("wiring"))
            .findFirst().orElseThrow().code().toString();
    }

    private MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    private GraphitronSchema buildSchema(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
