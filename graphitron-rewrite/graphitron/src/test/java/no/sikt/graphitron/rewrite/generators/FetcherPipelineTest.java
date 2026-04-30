package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.DataFetcherKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Integration tests for the full fetcher class pipeline: SDL schema → {@link GraphitronSchema} →
 * generated class list.
 *
 * <p>Verifies that {@link TypeFetcherGenerator} produces exactly one {@code *Fetchers} class
 * per GraphQL type that is a {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType},
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}, or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.RootType}.
 */
@PipelineTier
class FetcherPipelineTest {

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
        var sdl = """
            type Container @record { value: String }
            type Query { dummy: String }
            """;
        var bodies = fetcherBodies(sdl);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Container", "value")).isPresent();
    }

    @Test
    void propertyField_onRecordType_fetchersHasNoMethods() {
        var fetchers = findSpec("ContainerFetchers", """
            type Container @record { value: String }
            type Query { dummy: String }
            """);
        // PropertyField wired in ContainerWiring — the Fetchers class has no methods.
        assertThat(fetchers.methodSpecs()).isEmpty();
    }

    @Test
    void propertyField_untypedPojo_usesPropertyFetcher() {
        // @record with no backing class → PojoResultType(null) → PropertyDataFetcher.fetching(...)
        var sdl = """
            type Container @record { value: String }
            type Query { dummy: String }
            """;
        var bodies = fetcherBodies(sdl);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Container", "value"))
            .contains(DataFetcherKind.PROPERTY_FETCHER);
    }

    @Test
    void recordField_onRecordType_hasWiringEntry() {
        var sdl = """
            type FilmStats @record { count: Int }
            type FilmDetails @record { stats: FilmStats }
            type Query { dummy: String }
            """;
        var bodies = fetcherBodies(sdl);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmDetails", "stats")).isPresent();
    }

    @Test
    void recordField_onRecordType_fetchersHasNoMethods() {
        var fetchers = findSpec("FilmDetailsFetchers", """
            type FilmStats @record { count: Int }
            type FilmDetails @record { stats: FilmStats }
            type Query { dummy: String }
            """);
        // RecordField wired in FilmDetailsWiring — the Fetchers class has no methods.
        assertThat(fetchers.methodSpecs()).isEmpty();
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
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<org.jooq.Record>>");
    }

    @Test
    void recordTableField_onRecordType_hasRowsMethod() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_TABLE_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("rowsLanguage");
    }

    // ===== @record parent — RecordLookupTableField =====
    //
    // Backing class is the real jOOQ FilmRecord from graphitron-fixtures — a TableRecord
    // bound to "film", classifying the parent as JooqTableRecordType. This lets parsePath anchor on
    // the parent table and resolve the two-hop film → film_actor → actor path, matching the shape
    // the execution tests exercise in graphitron-test.

    private static final String RECORD_LOOKUP_TABLE_SDL = """
            type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              actorsByLookup(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
                {key: "film_actor_film_id_fkey"},
                {key: "film_actor_actor_id_fkey"}
              ])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """;

    @Test
    void recordLookupTableField_onRecordType_hasAsyncDataFetcher() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_LOOKUP_TABLE_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("actorsByLookup");
    }

    @Test
    void recordLookupTableField_onRecordType_asyncDataFetcherReturnsCompletableFutureListRecord() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_LOOKUP_TABLE_SDL);
        assertThat(method(fetchers, "actorsByLookup").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
    }

    @Test
    void recordLookupTableField_onRecordType_hasRowsMethod() {
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_LOOKUP_TABLE_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("rowsActorsByLookup");
    }

    @Test
    void recordLookupTableField_onRecordType_hasInputRowsHelper() {
        // Lookup-input VALUES helper — distinguishes RecordLookupTableField from RecordTableField.
        var fetchers = findSpec("FilmDetailsFetchers", RECORD_LOOKUP_TABLE_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("actorsByLookupInputRows");
    }

    // ===== Column fields → wired via ColumnFetcher =====

    @Test
    void wiring_columnField_usesColumnFetcher() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { dummy: String }
            """;
        assertThat(TypeSpecAssertions.wiringFor(fetcherBodies(sdl), "Film", "title"))
            .contains(DataFetcherKind.COLUMN_FETCHER);
    }

    // Dropped "columnField_withFieldDirective_usesRemappedColumn": the specific jOOQ column
    // referenced by the ColumnFetcher (FILM_ID vs TITLE) is body-content. Compile tier catches a
    // wrong Tables.FILM.<X> reference; execution tier catches wrong values. The classifier's
    // @field(name:) handling is covered separately by GraphitronSchemaBuilderTest.

    // ===== Root query table fields =====

    @Test
    void queryTableField_list_returnsResultRecord() {
        var films = method(findSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """), "films");
        assertThat(films.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void queryTableField_single_returnsRecord() {
        var film = method(findSpec("QueryFetchers", """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """), "film");
        assertThat(film.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

    @Test
    void queryTableField_withArgument_generatesConditionsClass() {
        var schema = buildSchema("""
            type Film @table(name: "film") { title: String, film_id: Int }
            type Query { film(film_id: Int!): Film }
            """);
        var conditionsClasses = TypeConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
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
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
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
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
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

    // ===== Helpers =====

    private List<String> generate(String sdl) {
        return TypeFetcherGenerator.generate(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .map(TypeSpec::name)
            .toList();
    }

    private TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherGenerator.generate(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
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

    private java.util.Map<String, CodeBlock> fetcherBodies(String sdl) {
        return FetcherRegistrationsEmitter.emit(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
    }

    private GraphitronSchema buildSchema(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
