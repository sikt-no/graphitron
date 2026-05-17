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
    // Backing class is the real jOOQ FilmRecord from graphitron-sakila-db — a TableRecord
    // bound to "film", classifying the parent as JooqTableRecordType. This lets parsePath anchor on
    // the parent table and resolve the two-hop film → film_actor → actor path, matching the shape
    // the execution tests exercise in graphitron-sakila-example.

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

    // ===== ErrorsField — wired via PropertyDataFetcher passthrough =====

    private static final String ERRORS_FIELD_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union BehandleSakError = ValidationErr | DbErr
            type BehandleSakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                ok: Boolean
                errors: [BehandleSakError!]
            }
            type Query { x: String }
            """;

    @Test
    void errorsField_onRecordPayload_wiringUsesPropertyFetcher() {
        // ErrorsField is a passthrough off the parent payload's errors accessor; the runtime
        // carrier (per-error dispatch + try/catch wrapping) ships later in error-handling-parity.md.
        var bodies = fetcherBodies(ERRORS_FIELD_SDL);
        assertThat(TypeSpecAssertions.wiringFor(bodies, "BehandleSakPayload", "errors"))
            .contains(DataFetcherKind.PROPERTY_FETCHER);
    }

    @Test
    void errorsField_onRecordPayload_fetchersClassEmitsNoMethodForIt() {
        // ErrorsField is in IMPLEMENTED_LEAVES with a no-op dispatch arm — no per-field method
        // is emitted; the wiring entry is the entire footprint.
        var fetchers = findSpec("BehandleSakPayloadFetchers", ERRORS_FIELD_SDL);
        assertThat(fetchers.methodSpecs()).extracting(MethodSpec::name).doesNotContain("errors");
    }

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
            type SakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.SakPayload"}) {
                data: String
                errors: [SakError]
            }
            type Query {
                sak: SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSak"})
            }
            """;

    @Test
    void serviceField_withResolvedErrorChannel_catchArmCallsErrorRouterDispatch() {
        // R12 §3 end-to-end: classifier resolves the ErrorChannel for a @service field whose
        // payload exposes an errors slot, then the emitter wires the catch arm through
        // ErrorRouter.dispatch with the channel's mapping-table constant. Counterpart to the
        // unit test queryServiceRecordField_withErrorChannel_* but going through the full
        // SDL → classifier → emitter pipeline.
        var sak = method(findSpec("QueryFetchers", SAK_DISPATCH_SDL), "sak");
        var body = sak.code().toString();
        assertThat(body).contains("ErrorRouter.dispatch");
        assertThat(body).contains("ErrorMappings.SAK_PAYLOAD");
        assertThat(body).doesNotContain("ErrorRouter.redact");
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

    // ===== R154 §2: mutable-bean payload shape admission =====

    /** Same SDL as SAK_DISPATCH_SDL but referencing the setter-shape SakPayload sibling class.
     *  Service method returns the setter-shape type directly (legacy passthrough), so only the
     *  ErrorChannel resolves against the setter-shape payload class. */
    private static final String SETTER_SHAPE_SAK_SDL = """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.SetterShapeSakPayload"}) {
                data: String
                errors: [SakError]
            }
            type Query {
                sak: SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSetterSak"})
            }
            """;

    @Test
    void serviceMutation_setterShapePayload_emitsSetterFactory() {
        // Mutable-bean payload: classifier resolves ErrorsSlot.SetterMethod; emitter prints the
        // multi-statement factory body (var p = new Payload(); p.setX(...); p.setErrors(errors);
        // ...; return p;) inside the catch-arm payload-factory lambda.
        var sak = method(findSpec("QueryFetchers", SETTER_SHAPE_SAK_SDL), "sak");
        var body = sak.code().toString();
        assertThat(body).contains("ErrorRouter.dispatch");
        assertThat(body).contains("errors -> {");
        assertThat(body).contains("new no.sikt.graphitron.codereferences.dummyreferences.SetterShapeSakPayload()");
        assertThat(body).contains(".setErrors(errors)");
        assertThat(body).contains(".setData(null)");
        assertThat(body).contains("return p;");
    }

    @Test
    void serviceMutation_allFieldsCtorPayload_emitsCtorFactory_unchanged() {
        // Regression cover: the all-fields-ctor path still emits the positional new Payload(...)
        // expression, with no setter calls.
        var sak = method(findSpec("QueryFetchers", SAK_DISPATCH_SDL), "sak");
        var body = sak.code().toString();
        assertThat(body).contains("ErrorRouter.dispatch");
        assertThat(body).contains("errors -> new no.sikt.graphitron.codereferences.dummyreferences.SakPayload(");
        assertThat(body).doesNotContain(".setErrors(errors)");
    }

    @Test
    void serviceMutation_bothShapesPresent_prefersCtorFactory() {
        // Canonical-over-bridge precedence: when a payload exposes both an all-fields ctor and
        // a no-arg ctor + setters, the classifier picks AllFieldsCtor (predicate 1 short-
        // circuits the walk). Emit reflects the positional ctor form.
        var sdl = SETTER_SHAPE_SAK_SDL
            .replace(
                "no.sikt.graphitron.codereferences.dummyreferences.SetterShapeSakPayload",
                "no.sikt.graphitron.codereferences.dummyreferences.BothShapesSakPayload")
            .replace("runSetterSak", "runBothShapesSak");
        var sak = method(findSpec("QueryFetchers", sdl), "sak");
        var body = sak.code().toString();
        assertThat(body).contains("ErrorRouter.dispatch");
        assertThat(body).contains("errors -> new no.sikt.graphitron.codereferences.dummyreferences.BothShapesSakPayload(");
        assertThat(body).doesNotContain(".setErrors(errors)");
    }

    @Test
    void dmlDeleteField_tableReturn_keepsExistingRawRowEmissionAndRedacts() {
        // Regression check for the existing @table-return path: payloadAssembly empty, raw row
        // returned, catch arm uses redact. The valueType lift narrows the payload local from
        // Object to org.jooq.Record on the ProjectedSingle arm.
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { dummy: String }
            type Mutation { deleteFilm(in: FilmInput!): Film @mutation(typeName: DELETE) }
            """;
        var deleteFilm = method(findSpec("MutationFetchers", sdl), "deleteFilm");
        var body = deleteFilm.code().toString();
        assertThat(body)
            .contains("Record payload = dsl")
            .contains(".returningResult(")
            .contains("ErrorRouter.redact(e, env)")
            .doesNotContain("ErrorRouter.dispatch");
        assertThat(deleteFilm.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

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
            .as("bulk arm casts in to List<Map<?,?>>")
            .contains("java.util.List<java.util.Map<?, ?>> in = (java.util.List<java.util.Map<?, ?>>) env.getArgument")
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
                title: String! @field(name: "title") @value
                description: String @field(name: "description") @value
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
        // Returns [Film!]! (ProjectedList) rather than [ID!]! to avoid the encoded-arm's
        // @node-type-on-table requirement; the bulk DELETE shape is identical regardless of
        // terminator, so ProjectedList is the right vehicle for the structural pin.
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmDeleteInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { dummy: String }
            type Mutation { deleteFilms(in: [FilmDeleteInput!]!): [Film!]! @mutation(typeName: DELETE) }
            """;
        var deleteFilms = method(findSpec("MutationFetchers", sdl), "deleteFilms");
        var body = deleteFilms.code().toString();
        assertThat(body)
            .contains("java.util.List<java.util.Map<?, ?>> in = (java.util.List<java.util.Map<?, ?>>) env.getArgument")
            .contains("if (in.isEmpty())")
            .contains("graphql.execution.DataFetcherResult.<java.util.List<org.jooq.Record>>newResult().data(java.util.List.of()).build()")
            .as("row-tuple IN form, regardless of key arity")
            .contains(".deleteFrom")
            .contains("org.jooq.impl.DSL.row(")
            .contains(".in(in.stream().map(row -> org.jooq.impl.DSL.row(")
            .contains("org.jooq.impl.DSL.val(row.get(\"filmId\")")
            .contains(".toList())")
            .contains(".returningResult(")
            .contains(".fetch(");
        assertThat(deleteFilms.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>");
    }

    @Test
    void dmlUpdateField_bulkInput_emitsValuesJoinWithUniformShapeAndDuplicateKeyAndDialectGuards() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            input FilmUpdateInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String! @field(name: "title") @value
                description: String @field(name: "description") @value
            }
            type Query { dummy: String }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): [Film!]! @mutation(typeName: UPDATE) }
            """;
        var updateFilms = method(findSpec("MutationFetchers", sdl), "updateFilms");
        var body = updateFilms.code().toString();
        assertThat(body)
            .as("Postgres dialect guard rides postDslGuard, before the in cast")
            .contains("if (!\"POSTGRES\".equals(dsl.dialect().family().name()))")
            .as("bulk arm casts in to List<Map<?,?>>")
            .contains("java.util.List<java.util.Map<?, ?>> in = (java.util.List<java.util.Map<?, ?>>) env.getArgument")
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
                title: String @field(name: "title") @value
                description: String @field(name: "description") @value
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
        // + runWithInputBean (the canonical R150 service-input-bean classification path) so
        // the SDL classifies cleanly; the validator pre-step then runs on top.
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
            type SakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.SakPayload"}) {
                data: String
                errors: [SakError]
            }
            type Query { x: String }
            type Mutation {
                runWithInputBean(input: TestInputBean): SakPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBean"})
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
            .contains("__validator.validate");
        // Negative half of the regression guard: the typed local feeds validate, not the raw
        // Map. The pre-step's "Object __arg_input = env.getArgument(...)" raw-coerce shape
        // belongs to the legacy fallback (used only when the assembled schema is missing).
        assertThat(body)
            .doesNotContain("Object __arg_input = env.getArgument");
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
