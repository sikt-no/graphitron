package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.DataFetcherKind;
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
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of());
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
    void recordType_producesAFetchersClass() {
        var classes = generate("""
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(classes).contains("ContainerFetchers");
    }

    // ===== @record parent — PropertyField and RecordField =====

    @Test
    void propertyField_onRecordType_hasWiringEntry() {
        var fetchers = findSpec("ContainerFetchers", """
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "value")).isPresent();
    }

    @Test
    void propertyField_onRecordType_noPerFieldMethod() {
        var fetchers = findSpec("ContainerFetchers", """
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        // PropertyField wired inline — only wiring() is generated, no per-field method
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).containsOnly("wiring");
    }

    @Test
    void propertyField_untypedPojo_usesPropertyFetcher() {
        // @record with no backing class → PojoResultType(null) → PropertyDataFetcher.fetching(...)
        var fetchers = findSpec("ContainerFetchers", """
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "value"))
            .contains(DataFetcherKind.PROPERTY_FETCHER);
    }

    @Test
    void recordField_onRecordType_hasWiringEntry() {
        var fetchers = findSpec("FilmDetailsFetchers", """
            type FilmStats @record { count: Int }
            type FilmDetails @record { stats: FilmStats }
            type Query { dummy: String }
            """);
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "stats")).isPresent();
    }

    @Test
    void recordField_onRecordType_noPerFieldMethod() {
        var fetchers = findSpec("FilmDetailsFetchers", """
            type FilmStats @record { count: Int }
            type FilmDetails @record { stats: FilmStats }
            type Query { dummy: String }
            """);
        // RecordField wired inline — only wiring() is generated
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).containsOnly("wiring");
    }

    // ===== @record parent — RecordTableField =====

    private static final String RECORD_TABLE_SDL = """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """;

    @Test
    void recordTableField_onRecordType_hasAsyncDataFetcher() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_TABLE_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("language");
    }

    @Test
    void recordTableField_onRecordType_asyncDataFetcherReturnsCompletableFuture() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_TABLE_SDL);
        assertThat(method(fetchers, "language").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<org.jooq.Record>");
    }

    @Test
    void recordTableField_onRecordType_hasRowsMethod() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_TABLE_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("rowsLanguage");
    }

    // ===== Column fields → wired via ColumnFetcher =====

    @Test
    void wiring_columnField_usesColumnFetcher() {
        var fetchers = findSpec("FilmFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """);
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "title"))
            .contains(DataFetcherKind.COLUMN_FETCHER);
    }

    // Dropped "columnField_withFieldDirective_usesRemappedColumn": the specific jOOQ column
    // referenced by the ColumnFetcher (FILM_ID vs TITLE) is body-content. Compile tier catches a
    // wrong Tables.FILM.<X> reference; execution tier catches wrong values. The classifier's
    // @field(name:) handling is covered separately by GraphitronSchemaBuilderTest.

    @Test
    void notGeneratedField_isExcluded() {
        var fetchers = findSpec("FilmFetchers", """
            type Film @table(name: "film") { title: String, hidden: String @notGenerated }
            type Query { dummy: String }
            """);
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "title")).isPresent();
        assertThat(TypeSpecAssertions.wiringFor(fetchers, "hidden")).isEmpty();
    }

    // ===== Root query table fields =====

    @Test
    void queryTableField_list_returnsResultRecord() {
        var films = method(findSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """), "films");
        assertThat(films.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void queryTableField_single_returnsRecord() {
        var film = method(findSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """), "film");
        assertThat(film.returnType().toString()).isEqualTo("org.jooq.Record");
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
