package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.schema.InputRecordGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions.DataFetcherKind;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            type Query { film: Film }
            """);
        assertThat(classes).contains("FilmFetchers");
    }

    @Test
    void multipleTableTypes_producesOneFetchersClassEach() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            type Query { film: Film actor: Actor }
            """);
        assertThat(classes).contains("FilmFetchers", "ActorFetchers");
    }

    @Test
    void classNameIsTypeNamePlusFetchers() {
        var classes = generate("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
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
            type Container { value: String }
            type Query {
                dummy: String
                c: Container @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeContainerRecord"})
            }
            """);
        assertThat(classes).contains("ContainerFetchers");
    }

    // ===== record-backed parent — PropertyField and RecordField =====

    @Test
    void propertyField_onRecordType_hasWiringEntry() {
        var sdl = """
            type Container { value: String }
            type Query {
                dummy: String
                c: Container @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeContainerRecord"})
            }
            """;
        var bodies = fetcherBodies(sdl);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Container", "value")).isPresent();
    }

    @Test
    void propertyField_onRecordType_reifiesReadMethod() {
        var fetchers = findSpec("ContainerFetchers", """
            type Container { value: String }
            type Query {
                dummy: String
                c: Container @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeContainerRecord"})
            }
            """);
        // R303: the PropertyField read is reified as a named source-only method on the class.
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("value");
    }

    @Test
    void propertyField_onBackedRecord_wrapsAccessorReadInLightFetcher() {
        // R276: a standalone untyped @record no longer yields an unbacked PropertyDataFetcher (it
        // is a NestingType now). R303: a reflection-backed record type reads its scalar field
        // through the (zero-arg) record accessor, reified as a named source-only method and
        // registered wrapped in LightFetcher (COLUMN_FETCHER kind), not an inline lambda.
        var sdl = """
            type Container { value: String }
            type Query {
                dummy: String
                c: Container @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeContainerRecord"})
            }
            """;
        var bodies = fetcherBodies(sdl);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "Container", "value"))
            .contains(DataFetcherKind.COLUMN_FETCHER);
        assertThat(findSpec("ContainerFetchers", sdl).methodSpecs())
            .extracting(MethodSpec::name).contains("value");
    }

    @Test
    void recordField_onRecordType_hasWiringEntry() {
        var sdl = """
            type FilmStats { count: Int }
            type FilmDetails { stats: FilmStats }
            type Query {
                dummy: String
                fd: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRecord"})
                fs: FilmStats @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmStatsRecord"})
            }
            """;
        var bodies = fetcherBodies(sdl);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmDetails", "stats")).isPresent();
    }

    @Test
    void recordField_onRecordType_reifiesReadMethod() {
        var fetchers = findSpec("FilmDetailsFetchers", """
            type FilmStats { count: Int }
            type FilmDetails { stats: FilmStats }
            type Query {
                dummy: String
                fd: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRecord"})
                fs: FilmStats @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmStatsRecord"})
            }
            """);
        // R303: the RecordField read is reified as a named source-only method on the class.
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).contains("stats");
    }

    // ===== record-backed parent — RecordTableField =====

    private static final String RECORD_TABLE_SDL = """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
              film: Film
              filmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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

    // ===== record-backed parent — RecordLookupTableField =====
    //
    // Backing class is the real jOOQ FilmRecord from graphitron-sakila-db — a TableRecord
    // bound to "film", classifying the parent as JooqTableRecordType. This lets parsePath anchor on
    // the parent table and resolve the two-hop film → film_actor → actor path, matching the shape
    // the execution tests exercise in graphitron-sakila-example.

    private static final String RECORD_LOOKUP_TABLE_SDL = """
            type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
            type FilmDetails {
              actorsByLookup(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
                {key: "film_actor_film_id_fkey"},
                {key: "film_actor_actor_id_fkey"}
              ])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
              film: Film
              filmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
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

    // R276: the legacy "record-backed payload with a developer-owned errors slot read via
    // PropertyDataFetcher" tests were deleted. That passthrough required a backed payload with no
    // producer (the removed @record-className idiom); a @service-produced errors payload now rides
    // the R244 Outcome WrapperArm transport, covered by SAK_DISPATCH_SDL / the OUTCOME_*_SDL tests
    // below.

    // ===== R12 §3 try/catch wrapper: end-to-end SDL exercising the dispatch arm =====

    private static final String SAK_DISPATCH_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query {
                sak: SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSak"})
            }
            """;

    @Test
    void serviceField_withResolvedErrorChannel_emitsOutcomeWrapperReturnType() {
        // R244 end-to-end: a @service field whose payload carries an errors field classifies to
        // ErrorChannel.Mapped, and buildServiceFetcherCommon lifts the fetcher's return to the
        // Outcome wrapper (DataFetcherResult<Outcome<X>>). The return type is the structural,
        // refactor-stable signal that the flip propagated from classifier to emitter; a revert to
        // the legacy payload-factory path would drop the Outcome parameterisation here (and fail the
        // ErrorChannel.Mapped assertion in ErrorChannelClassificationTest). The catch-arm body shape
        // (the Outcome.ErrorList mapping-walk + redact fallthrough) is pinned behaviourally by
        // ChannelCatchArmEmitterTest (unit) and the GraphQLQueryTest execution-tier round-trip, not
        // by a code-string assertion on the generated body (banned per rewrite-design-principles.adoc).
        var sak = method(findSpec("QueryFetchers", SAK_DISPATCH_SDL), "sak");
        assertThat(sak.returnType().toString())
            .contains("graphql.execution.DataFetcherResult")
            .contains("Outcome");
    }

    @Test
    void serviceField_withoutErrorChannel_catchArmCallsErrorRouterRedact() {
        // Counterpart: a @service field whose payload has no errors slot keeps the no-channel
        // disposition (redact). Distinct fixture from SAK_DISPATCH_SDL — the payload is a plain
        // scalar with no @error types reachable.
        var sdl = """
            type Query {
                count: Int
                    @service(service: {className: "no.sikt.graphitron.rewrite.test.services.SampleQueryService", method: "filmCount"})
            }
            """;
        var count = method(findSpec("QueryFetchers", sdl), "count");
        var body = count.code().toString();
        assertThat(body).contains("ErrorRouter.redact(e, env)");
        assertThat(body).doesNotContain("ErrorRouter.dispatch");
    }

    // ===== R268: @table DataLoader data field under a flipped @service Outcome payload =====
    //
    // The pairing R244's inventory lacked and the retired arm-switch allow-list wrongly rejected:
    // a RecordTableField (DataLoader-resolved @table field) sibling to a WrapperArm errors field
    // under a root @service payload. The data field still gets its async DataLoader fetcher (which
    // arm-switches inside that method, narrowing Outcome.Success and reading the key off
    // success.value()), and its registration is a real fetcher reference, never graphql-java's
    // default PropertyDataFetcher.
    private static final String OUTCOME_TABLE_FIELD_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) { path: [String!]! message: String! }
            union SakError = ValidationErr | DbErr
            type Language @table(name: "language") { name: String }
            type SakPayload {
                data: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
                errors: [SakError]
            }
            type Query {
                sak: SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSak"})
            }
            """;

    @Test
    void outcomePayload_tableDataField_emitsArmSwitchingDataLoaderFetcher() {
        // RecordTableField classification under the WrapperArm payload: the @table field gets its
        // async DataLoader fetcher method (the same CompletableFuture<DataFetcherResult<Record>>
        // shape as the non-outcome RecordTableField), not a stub or a registration fall-through.
        // Before R268 this schema failed validation outright (RecordTableField was off the
        // arm-switch allow-list); the emission here is the structural proof the false rejection is
        // gone. The arm-switch prelude (narrow Success / completedFuture(null) on ErrorList) is
        // pinned behaviourally by the GraphQLQueryTest execution round-trip, not a body-string here.
        var fetchers = findSpec("SakPayloadFetchers", OUTCOME_TABLE_FIELD_SDL);
        assertThat(method(fetchers, "language").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<org.jooq.Record>>");
    }

    // R268 column-read arm-switch: an @service Outcome payload backed by a jOOQ TableRecord (the
    // service returns FilmRecord) carries a column-projected data field. Under the wrapper transport
    // its inline read must be the ColumnFetcher get inlined onto success.value(), not the bare
    // ColumnFetcher (which would read off the Outcome object) and not a generation-time throw. The
    // spec's "Per-shape mechanism" scopes the column inline-read in alongside the record-backed accessor.
    private static final String OUTCOME_COLUMN_FIELD_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) { path: [String!]! message: String! }
            union SakError = ValidationErr | DbErr
            type FilmRecordPayload {
                title: String @field(name: "TITLE")
                errors: [SakError]
            }
            type Query {
                film: FilmRecordPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """;

    @Test
    void outcomePayload_columnDataField_armSwitchesInlineReadOnSuccessValue() {
        // R303: the arm-switch read is reified onto FilmRecordPayloadFetchers as a named source-only
        // method (narrow Success, return the column off success.value(); null on the ErrorList arm)
        // and registered wrapped in LightFetcher (COLUMN_FETCHER kind) — not a bare column read off
        // the Outcome object and not an IllegalStateException at generation. Building the spec proves
        // the throw is gone; COLUMN_FETCHER proves it stays on the light path; the reified method's
        // presence proves the read is a findable symbol. Body shape is pinned structurally, not by a
        // code-string assertion (banned).
        var bodies = fetcherBodies(OUTCOME_COLUMN_FIELD_SDL);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "FilmRecordPayload", "title"))
            .contains(DataFetcherKind.COLUMN_FETCHER);
        assertThat(findSpec("FilmRecordPayloadFetchers", OUTCOME_COLUMN_FIELD_SDL).methodSpecs())
            .extracting(MethodSpec::name).contains("title");
    }

    @Test
    void outcomePayload_tableDataField_wiringIsMethodReferenceNotPropertyFetcher() {
        // Structural-check counterpart: the @table data field resolves through a graphitron-emitted
        // fetcher (a method reference into SakPayloadFetchers), never graphql-java's default
        // PropertyDataFetcher that would read a property off the Outcome source object. This is the
        // invariant GraphitronSchemaValidator.validateOutcomeChildArmSwitch now enforces structurally
        // in place of allow-list membership.
        var bodies = fetcherBodies(OUTCOME_TABLE_FIELD_SDL);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "SakPayload", "language"))
            .contains(DataFetcherKind.METHOD_REFERENCE);
    }

    // ===== R154 §2: mutable-bean payload shape admission =====
    //
    // R244: serviceMutation_setterShapePayload_emitsSetterFactory,
    // serviceMutation_allFieldsCtorPayload_emitsCtorFactory_unchanged, and
    // serviceMutation_bothShapesPresent_prefersCtorFactory deleted. They pinned the catch-arm
    // payload-factory lambda (ctor / setter construction shapes), which @service fields no longer
    // emit, they now wrap success in Outcome.Success and the error path in Outcome.ErrorList, with
    // no developer payload constructed. The Outcome flip is pinned structurally by
    // serviceField_withResolvedErrorChannel_emitsOutcomeWrapperReturnType above, and behaviourally by
    // the ChannelCatchArmEmitter unit test plus the execution-tier round-trip; the construction-shape
    // admission itself stays covered by PayloadConstructionShapeTest and retires in commit 4.

    // R287: the @table-return DELETE path is removed (the row is gone after the statement; RETURNING
    // carries only the PK, so a full @table projection is impossible). The former
    // dmlDeleteField_tableReturn_keepsExistingRawRowEmissionAndRedacts pipeline proof of that path
    // retires with it; the ID-return DELETE emission is covered by the encoded-arm pipeline/execution
    // tests, and the @table-return ProjectedSingle emission stays covered by the INSERT/UPDATE roots.

    // ===== Bulk DML mutations (R77 Phase E) =====
    //
    // Pipeline-tier coverage of the four bulk-arm structural shapes. Per spec, no code-string
    // assertions on the generated SQL chain itself; we assert on the structural invariants the
    // emitter contracts: list-cast + empty-list short-circuit (both centralised in
    // buildDmlFetcher), verb-specific row-loop shape (valuesOfRows for INSERT/UPSERT, row-tuple
    // IN for DELETE, VALUES-join for UPDATE), per-cell missing-vs-null dispatch on
    // INSERT/UPSERT, dynamic SET on UPDATE/UPSERT-with-doUpdate, and the bulk UPDATE guards
    // (uniform-shape, duplicate-tuple) plus its Postgres dialect guard.

    @Test
    void dmlInsertField_bulkInput_emitsValuesOfRowsWithContainsKeyDispatchAndEmptyListShortCircuit() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                title: String! @field(name: "title")
                languageId: Int! @field(name: "language_id")
            }
            type Query { dummy: String }
            type Mutation { createFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: INSERT) }
            """;
        var createFilms = method(findSpec("MutationFetchers", sdl), "createFilms");
        var body = createFilms.code().toString();
        // JavaPoet writes fully-qualified type names; assertions track the canonical FQN so a
        // change in import resolution would surface here, not as a brittle short-name regex.
        assertThat(body)
            .as("bulk arm binds in as List<Map<?,?>> (getArgument inference, no cast)")
            .contains("java.util.List<java.util.Map<?, ?>> in = env.getArgument")
            .as("empty-list short-circuit returns typed empty without round-trip")
            .contains("if (in.isEmpty())")
            .contains("graphql.execution.DataFetcherResult.<java.util.List<org.jooq.Record>>newResult().data(java.util.List.of()).build()")
            .as("multi-row VALUES via stream + valuesOfRows")
            .contains(".valuesOfRows(in.stream()")
            .contains("org.jooq.impl.DSL.row(")
            .contains(".toList()")
            .as("per-cell missing-vs-null dispatch on row.containsKey")
            .contains("row.containsKey(\"title\")")
            .contains("row.containsKey(\"languageId\")")
            .contains("org.jooq.impl.DSL.defaultValue(")
            .contains("org.jooq.impl.DSL.val(row.get(")
            .as("no single-row in.containsKey on the bulk arm")
            .doesNotContain("in.containsKey(");
        assertThat(createFilms.returnType().toString())
            .as("DataFetcherResult parameter narrows from Object to List<Record> on the projected-list arm")
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>");
    }

    @Test
    void dmlUpsertField_bulkInput_rejectedUnderR144() {
        // R144 retires UPSERT generation pending R145 (mutation-cardinality-safety-upsert).
        // The classifier surfaces a deferred rejection at MutationInputResolver before any
        // fetcher is emitted, so the structural pin lives at the rejection message instead of
        // the SQL chain.
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmUpsertInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
                description: String @field(name: "description")
            }
            type Query { dummy: String }
            type Mutation { upsertFilms(in: [FilmUpsertInput!]!): [Film!]! @mutation(typeName: UPSERT) }
            """;
        var spec = findSpec("MutationFetchers", sdl);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .as("UPSERT generation is suppressed under R144; no upsertFilms fetcher emitted")
            .doesNotContain("upsertFilms");
    }

    @Test
    void dmlDeleteField_bulkInput_emitsRowTupleInWithStreamMap() {
        // R287: a bulk DELETE returns [ID!]! (the encoded-PK arm); the @table-return ([Film!]!) path
        // is removed. Film is @node so the encoder resolves. The bulk DELETE SQL chain (row-tuple IN
        // over the stream-mapped input) is the structural subject here, independent of the terminator.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { dummy: String }
            type Mutation { deleteFilms(in: [FilmDeleteInput!]!): [ID!]! @mutation(typeName: DELETE) }
            """;
        var deleteFilms = method(findSpec("MutationFetchers", sdl), "deleteFilms");
        var body = deleteFilms.code().toString();
        assertThat(body)
            .contains("java.util.List<java.util.Map<?, ?>> in = env.getArgument")
            .contains("if (in.isEmpty())")
            .as("row-tuple IN form, regardless of key arity")
            .contains(".deleteFrom")
            .contains("org.jooq.impl.DSL.row(")
            .contains(".in(in.stream().map(row -> org.jooq.impl.DSL.row(")
            .contains("org.jooq.impl.DSL.val(row.get(\"filmId\")")
            .contains(".toList())")
            .contains(".returningResult(")
            .contains(".fetch(");
    }

    @Test
    void dmlUpdateField_bulkInput_emitsValuesJoinWithUniformShapeAndDuplicateKeyAndDialectGuards() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title")
                description: String @field(name: "description")
            }
            type Query { dummy: String }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): [Film!]! @mutation(typeName: UPDATE) }
            """;
        var updateFilms = method(findSpec("MutationFetchers", sdl), "updateFilms");
        var body = updateFilms.code().toString();
        assertThat(body)
            .as("Postgres dialect guard rides postDslGuard, before the in binding")
            .contains("if (!\"POSTGRES\".equals(dsl.dialect().family().name()))")
            .as("bulk arm binds in as List<Map<?,?>> (getArgument inference, no cast)")
            .contains("java.util.List<java.util.Map<?, ?>> in = env.getArgument")
            .contains("if (in.isEmpty())")
            .as("uniform-shape guard runs before the SET map is built")
            .contains("java.util.Set<?> firstKeys = in.get(0).keySet()")
            .contains("if (!in.get(rowIdx).keySet().equals(firstKeys))")
            .as("v-table column-name list and per-row cells walk firstKeys for setFields")
            .contains("vColNames.add(")
            .contains("if (firstKeys.contains(\"title\"))")
            .contains("if (firstKeys.contains(\"description\"))")
            .as("imperative for-loop builds vRows (control flow inside, not a stream lambda)")
            .contains("for (java.util.Map<?, ?> row : in)")
            .contains("vRows.add(")
            .contains("org.jooq.impl.DSL.values(vRows.toArray(")
            .contains(".as(\"v\", vColNames.toArray")
            .as("SET map references v.field(t.col), not bound values directly")
            .contains("sets.put(")
            .contains("v.field(")
            .as("no-set-fields-present runtime check")
            .contains("if (sets.isEmpty())")
            .as("duplicate-lookup-key guard via HashSet<List<Object>>")
            .contains("seenKeys")
            .contains("java.util.List.of(row.get(\"filmId\"))")
            .contains("if (seenKeys.size() != in.size())")
            .as("chain shape: update().set().from(v).where()")
            .contains(".update(")
            .contains(".set(sets)")
            .contains(".from(v)")
            .contains(".where(");
        assertThat(updateFilms.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>");
    }

    @Test
    void dmlUpsertField_doNothingMode_rejectedUnderR144() {
        // R144 retires UPSERT generation: the doNothing / doUpdate dispatch is no longer
        // exercised at the fetcher tier. The classifier rejects upstream; no fetcher is
        // emitted.
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmUpsertNoSetInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
            }
            type Query { dummy: String }
            type Mutation { upsertFilms(in: [FilmUpsertNoSetInput!]!): [Film!]! @mutation(typeName: UPSERT) }
            """;
        var spec = findSpec("MutationFetchers", sdl);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .doesNotContain("upsertFilms");
    }

    @Test
    void dmlSingleRowUpdateField_emitsDynamicSetWalkOverInKeySet() {
        // Phase E also fixes the legacy missing-vs-null bug on single-row UPDATE: omitted
        // columns drop out of SET (PATCH semantics), explicit nulls bind typed null. Pin the
        // structural shift from "unconditional walk over setFields()" to
        // "if (in.containsKey(name)) { sets.put(...) }".
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String @field(name: "title")
                description: String @field(name: "description")
            }
            type Query { dummy: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """;
        var updateFilm = method(findSpec("MutationFetchers", sdl), "updateFilm");
        var body = updateFilm.code().toString();
        assertThat(body)
            .as("single-row arm keeps Map<?,?> cast (no List<Map<?,?>>)")
            .contains("java.util.Map<?, ?> in = (java.util.Map<?, ?>) env.getArgument")
            .doesNotContain("java.util.List<java.util.Map<?, ?>>")
            .as("dynamic SET walk gates each setField on in.containsKey")
            .contains("if (in.containsKey(\"title\"))")
            .contains("if (in.containsKey(\"description\"))")
            .contains("sets.put(")
            .contains("org.jooq.impl.DSL.val(in.get(")
            .as("no-set-fields-present runtime check")
            .contains("if (sets.isEmpty())")
            .contains("@mutation(typeName: UPDATE) call has no settable fields present");
    }

    // ===== Column fields → wired via ColumnFetcher =====

    @Test
    void wiring_columnField_usesColumnFetcher() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
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
        var conditionsClasses = TypeConditionsGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
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
            type Query { language: Language }
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
            type Query { language: Language }
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
            type Query { language: Language }
            extend type Language {
                films: [Film!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(languageFetchers.methodSpecs()).extracting(MethodSpec::name).contains("rowsFilms");
    }

    // ===== @service fields =====

    @Test
    void serviceField_dataFetcherReturnsCompletableFutureListProjectedRecord() {
        // R285: ServiceTableField lifts the service result back through a $fields re-projection,
        // so the loader value is the projected org.jooq.Record, not the developer-returned XRecord.
        var languageFetchers = findSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                films(filter: String): [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilms"},
                    contextArguments: ["tenantId"]
                )
            }
            """);
        assertThat(method(languageFetchers, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
        // Positional container: the lifted rows method returns List<List<Record>> (idx-scattered
        // per-parent lists of the projected Record), confirming lift routing for POSITIONAL_LIST.
        assertThat(method(languageFetchers, "loadFilms").returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_mappedContainer_rowsMethodReturnsMapOfProjectedRecord() {
        // R285: mapped-container sibling of the positional case above. Set keys + Map return
        // classify the loader as MAPPED_SET; the lifted rows method returns
        // Map<Row1<Integer>, List<Record>> — the projected Record (lift routing), keyed back to
        // each parent. Together the two tests cover both loader containers through the lift.
        var languageFetchers = findSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                films: [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"}
                )
            }
            """);
        assertThat(method(languageFetchers, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
        assertThat(method(languageFetchers, "loadFilms").returnType().toString())
            .isEqualTo("java.util.Map<org.jooq.Row1<java.lang.Integer>, java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_rowsMethodIsNamedLoadPlusFieldName() {
        var languageFetchers = findSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            type Film @table(name: "film") { title: String }
            type Query { language: Language }
            extend type Language {
                films(filter: String): [Film!]! @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilms"},
                    contextArguments: ["tenantId"]
                )
            }
            """);
        assertThat(languageFetchers.methodSpecs()).extracting(MethodSpec::name).contains("loadFilms");
    }

    @Test
    void serviceField_enumLeaf_mapped_rowsMethodReturnsFlatMapNotDoublyNested() {
        // R364: a non-built-in scalar leaf (enum, or an unregistered custom scalar) used to fall
        // the per-key element type back to the service method's whole Map<KeyRecord, V>, which
        // outerRowsReturnType then wrapped once more — emitting Map<Row1<Integer>,
        // Map<Row1<Integer>, String>> and failing to compile. The enum leaf is the DB text
        // (String); the per-key V is that String, so the rows method must be the flat
        // Map<Row1<Integer>, String>. The Int sibling below resolves through the spec-built-in
        // path and was always flat; the pair confirms the fix touches only the non-built-in leaf.
        var languageFetchers = findSpec("LanguageFetchers", """
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            enum Rating { G PG R }
            type Query { language: Language }
            extend type Language {
                rating: Rating @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRatingMapped"}
                )
                rank: Int @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"}
                )
            }
            """);
        assertThat(method(languageFetchers, "loadRating").returnType().toString())
            .isEqualTo("java.util.Map<org.jooq.Row1<java.lang.Integer>, java.lang.String>");
        assertThat(method(languageFetchers, "loadRank").returnType().toString())
            .isEqualTo("java.util.Map<org.jooq.Row1<java.lang.Integer>, java.lang.Integer>");
    }

    @Test
    void serviceField_enumLeaf_mapped_wrongContainer_rejectedAtClassifyTime() {
        // R364 step 2: the validator no longer skips a non-built-in scalar leaf. A mapped (Set-keyed)
        // enum field whose method returns a bare List — unpeelable into the expected Map<K, V> —
        // is rejected at classify time rather than left to miscompile on the generated return line.
        var schema = buildSchema("""
            type Language @table(name: "language") { languageId: Int @field(name: "language_id") }
            enum Rating { G PG R }
            type Query { language: Language }
            extend type Language {
                rating: Rating @service(
                    service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRatingWrongContainer"}
                )
            }
            """);
        var rating = schema.field("Language", "rating");
        assertThat(rating).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        var reason = ((no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) rating).rejection().message();
        assertThat(reason)
            .contains("getRatingWrongContainer")
            .contains("'java.util.Map'")
            .contains("Row1<Integer>")
            .contains("List<String>");
    }

    // ===== Helpers =====

    private List<String> generate(String sdl) {
        return TypeFetcherGenerator.generate(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
            .map(TypeSpec::name)
            .toList();
    }

    // ===== R94: graphitron-emitted input classes =====

    @Test
    void inputRecord_scalar_emitsFromMapAndValidatesAgainstRecord() {
        // Pipeline-tier R94: a single-scalar input type produces one input class with one
        // component and a static fromMap factory keyed by the SDL field name.
        var sdl = """
            input FilmIdInput { filmId: Int! }
            type Query { dummy(in: FilmIdInput): String }
            """;
        var spec = inputRecordSpec("FilmIdInput", sdl);
        assertThat(spec.methodSpecs())
            .extracting(MethodSpec::name)
            .contains("fromMap", "filmId");
        var fromMap = method(spec, "fromMap");
        // The factory dispatches on the scalar component by SDL field name.
        assertThat(fromMap.code().toString()).contains("\"filmId\"");
    }

    @Test
    void inputRecord_list_emitsListComponent() {
        // Pipeline-tier R94: a list-of-scalar input emits a List<Integer>-shaped component
        // and the fromMap factory streams element-wise (pass-through cast).
        var sdl = """
            input FilmIdsInput { filmIds: [Int!]! }
            type Query { dummy(in: FilmIdsInput): String }
            """;
        var spec = inputRecordSpec("FilmIdsInput", sdl);
        // The emitted accessor's declared return type proves the List<Integer> shape.
        var accessor = method(spec, "filmIds");
        assertThat(accessor.returnType().toString())
            .isEqualTo("java.util.List<java.lang.Integer>");
    }

    @Test
    void inputRecord_nested_recursesCoercer() {
        // Pipeline-tier R94: a nested-input component emits a List<FilmIdItem>-shaped
        // accessor on the parent, and the parent's fromMap recurses FilmIdItem.fromMap.
        var sdl = """
            input FilmIdItem { filmId: Int! }
            input FilmsByPathInput { films: [FilmIdItem!]! }
            type Query { dummy(in: FilmsByPathInput): String }
            """;
        var parentSpec = inputRecordSpec("FilmsByPathInput", sdl);
        // The List element type lifts to the sibling class's ClassName.
        var accessor = method(parentSpec, "films");
        assertThat(accessor.returnType().toString())
            .isEqualTo("java.util.List<" + DEFAULT_OUTPUT_PACKAGE + ".inputs.FilmIdItem>");
        var fromMap = method(parentSpec, "fromMap");
        // The recursion is structural — the parent's factory references the sibling class.
        assertThat(fromMap.code().toString())
            .contains(DEFAULT_OUTPUT_PACKAGE + ".inputs.FilmIdItem")
            .contains(".fromMap(");
    }

    @Test
    void inputRecord_unreachable_emitsNoRecord() {
        // Pipeline-tier R94: an SDL input type not reachable from any field argument is dead
        // schema; the closure walker (with the assembled schema in hand) ignores it, so no
        // class is emitted. The unreachable case requires the assembled schema since the
        // closure walk reads SDL field arguments off the assembled GraphQLObjectType.
        var sdl = """
            input ReachedInput { id: Int! }
            input UnreachedInput { id: Int! }
            type Query { dummy(in: ReachedInput): String }
            """;
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var classes = InputRecordGenerator.generate(bundle.model(), bundle.assembled(), DEFAULT_OUTPUT_PACKAGE)
            .stream().map(TypeSpec::name).toList();
        assertThat(classes).contains("ReachedInput");
        assertThat(classes).doesNotContain("UnreachedInput");
    }

    @Test
    void inputRecord_validatorPreStep_receivesTypedRecordNotMap() {
        // Pipeline-tier R94 regression guard: the rewired validator pre-step on a mutation
        // service field with a ValidationHandler-bearing @error type materialises the
        // graphitron-emitted class via <InputName>.fromMap(...) and walks the typed local
        // through validator.validate(...). Drifting the pre-step back to a raw Map walk
        // (validate(env.getArgument(...))) fails the contains assertion. Uses TestInputBean
        // + runSakWithInputBean (the canonical R150 service-input-bean classification path
        // returning the SakPayload directly) so the SDL classifies cleanly; the validator
        // pre-step then runs on top.
        var sdl = """
            enum TestInputBeanEnum { LOW HIGH }
            input TestInputNested { key: String, value: String }
            input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { x: String }
            type Mutation {
                runWithInputBean(input: TestInputBean): SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSakWithInputBean"})
            }
            """;
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var specs = TypeFetcherGenerator.generate(bundle.model(), bundle.assembled(), DEFAULT_OUTPUT_PACKAGE);
        var mutationFetchers = specs.stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst()
            .orElseThrow();
        var run = method(mutationFetchers, "runWithInputBean");
        var body = run.code().toString();
        // The validator pre-step walks the typed instance, not the raw Map.
        assertThat(body)
            .contains(DEFAULT_OUTPUT_PACKAGE + ".inputs.TestInputBean")
            .contains(".fromMap(")
            .contains("validator.validate");
        // Negative half of the regression guard: the typed local feeds validate, not the raw
        // Map. The pre-step's "Object arg_input = env.getArgument(...)" raw-coerce shape
        // belongs to the legacy fallback (used only when the assembled schema is missing).
        assertThat(body)
            .doesNotContain("Object arg_input = env.getArgument");
    }

    /**
     * Helper for the four R94 SDL → input-class pipeline cases: produces the {@code TypeSpec}
     * the R94 generator emits for {@code typeName}, asserting the class is present in the
     * emitted set. The model-only build path is enough for the input-class shape; the
     * regression-guard case above uses {@code buildBundle} because the validator pre-step
     * resolution needs the assembled graphql-java schema.
     */
    private TypeSpec inputRecordSpec(String typeName, String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var specs = InputRecordGenerator.generate(bundle.model(), bundle.assembled(), DEFAULT_OUTPUT_PACKAGE);
        return specs.stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Input class '" + typeName + "' not emitted; got: "
                    + specs.stream().map(TypeSpec::name).toList()));
    }

    private TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherGenerator.generate(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE).stream()
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
        return FetcherRegistrationsEmitter.emit(buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE);
    }

    private GraphitronSchema buildSchema(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
