package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmEndorsementRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.DSLContext;
import org.jooq.Result;

/**
 * Minimal service stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @service} fields are correctly classified when the service class exists
 * in the classpath.
 *
 * <p>The {@code String}-returning methods are kept for tests that don't care about
 * the field's resolved return type (parameter-classification tests, return-type
 * mismatch negative cases). Methods returning specific jOOQ record classes
 * ({@link FilmRecord}, {@link LanguageRecord}, etc.) exercise the strict
 * service-return-type validation in {@code ServiceCatalog.reflectServiceMethod}
 * against {@code FieldBuilder.computeExpectedServiceReturnType}: a {@code @service}
 * field whose resolved return type is {@code @table}-bound (or a record-backed type
 * with a backing class) requires the developer's method to declare a matching
 * parameterized return type.
 *
 * <p>Requires the {@code -parameters} compiler flag for context-arg variants
 * (the project's {@code pom.xml} already sets this flag).
 */
class TestServiceStub {

    // ===== String-returning methods (return-type mismatch / parameter-classification tests) =====

    /** No-arg, returns {@code String} — used for parameter-classification tests. */
    public static String get() { throw new UnsupportedOperationException(); }

    /**
     * R275 — no-arg query-side producer for the {@code FilmDetails @record} fixture payload,
     * returning the {@link TestFilmDetailsDto} backing so the payload grounds via R96 reflection
     * (the dangling-type-reference soundness pass rejects unbacked payload returns).
     */
    public static TestFilmDetailsDto getDetails() { throw new UnsupportedOperationException(); }

    /** R275 — mutation-side sibling of {@link #getDetails}. */
    public static TestFilmDetailsDto runDetails() { throw new UnsupportedOperationException(); }

    /**
     * R190 fixture: {@code @service} with one {@code String userId} contextArgument; pairs with
     * {@link #getRatingByFnr} on the cross-site factory pipeline test (different name, different
     * type, so the factory's parameter list reflects both alphabetically).
     */
    public static String getRatingByUser(String userId) { throw new UnsupportedOperationException(); }

    /**
     * R190 fixture: {@code @service} with a {@code Long fnr} contextArgument; pairs with
     * {@link #getRatingByUser} so the factory's emitted parameter list carries both
     * {@code (DSLContext defaultDsl, Long fnr, String userId)} in alphabetical order.
     */
    public static String getRatingByFnr(Long fnr) { throw new UnsupportedOperationException(); }

    /**
     * R238 fixture: {@code @service} with one {@code Long userId} contextArgument; the
     * {@code userId} name shares with {@link #getRatingByUser} but the Java type is {@code Long},
     * not {@code String} — exercises the ContextArgumentClassifier's ServiceField harvest
     * disagreement path.
     */
    public static String getRatingByUserLong(Long userId) { throw new UnsupportedOperationException(); }

    /**
     * R238 fixture: a static service method with two {@link DSLContext} parameters in the same
     * method round. The walker's {@code MultipleDslContextSlots} invariant fires when more than
     * one DSL slot lands per round; the test verifies the rejection projects to a typed
     * {@code ServiceMethodCallError.MultipleDslContextSlots} arm carrying its
     * {@code lspCode()} through to LSP.
     */
    public static String getWithTwoDsls(DSLContext a, DSLContext b) { throw new UnsupportedOperationException(); }

    /** No-arg, returns {@code String} — used for mutation parameter-classification tests. */
    public static String run() { throw new UnsupportedOperationException(); }

    /** {@link DSLContext} param, returns {@code String}. */
    public static String getWithDsl(DSLContext dsl) { throw new UnsupportedOperationException(); }

    /** {@link DSLContext} + GraphQL arg, returns {@code String}. */
    public static String getByIdWithDsl(DSLContext dsl, String id) { throw new UnsupportedOperationException(); }

    /** {@link DSLContext} whose name collides with a GraphQL argument. */
    public static String getFilteredWithDsl(DSLContext filter) { throw new UnsupportedOperationException(); }

    /** Param that isn't DSLContext, isn't a GraphQL arg, and isn't a {@code List<?>}. */
    public static String getWithUnknown(Object opaque) { throw new UnsupportedOperationException(); }

    /**
     * Returns {@code Result<FilmRecord>} with a {@code List<Row1<Integer>>} keys parameter and
     * a non-SOURCES-shaped {@code LocalDate} payload parameter — used by R187 tests pinning that
     * a clearly non-SOURCES-adjacent type at a nested coordinate produces the arg-mismatch
     * diagnostic rather than "unrecognized sources type".
     */
    public static Result<FilmRecord> getFilmsWithLocalDate(java.util.List<org.jooq.Row1<Integer>> keys,
                                                          java.time.LocalDate input) {
        throw new UnsupportedOperationException();
    }

    // ===== FilmRecord-returning methods (positive-classification tests with @table-bound returns) =====

    /** Returns the specific {@link FilmRecord} — used by {@code @service} on a {@code Film} return. */
    public static FilmRecord getFilm() { throw new UnsupportedOperationException(); }

    /** Returns the specific {@link FilmRecord} — used by {@code @service} on a mutation Film return. */
    public static FilmRecord runFilm() { throw new UnsupportedOperationException(); }

    /** Returns a list of {@link FilmRecord} — used by {@code @service} on a {@code [Film!]!} return. */
    public static Result<FilmRecord> getFilms() { throw new UnsupportedOperationException(); }

    /**
     * Returns a {@link java.util.List List} of {@link FilmRecord} — used by {@code @service} on a
     * {@code [Film!]!} return to exercise the looser pair check that accepts either
     * {@code Result<XRecord>} or {@code List<XRecord>} at the root.
     */
    public static java.util.List<FilmRecord> getFilmsAsList() { throw new UnsupportedOperationException(); }

    /**
     * Returns {@link FilmRecord} but takes a {@code List<Row1<Integer>>} parameter — classifies
     * as {@link no.sikt.graphitron.rewrite.model.ParamSource.Sources}. Used to verify
     * Invariants §2 fires after the strict-return-type check passes.
     */
    public static FilmRecord getFilmWithSources(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a list of {@link FilmRecord} but takes a {@code List<Row1<Integer>>} parameter — used
     * for tests that target root-level {@code @service} on {@code [Film!]!} with a Sources param.
     */
    public static Result<FilmRecord> getFilmsWithSources(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== LanguageRecord-returning methods (for child @service on FilmDetails @record) =====

    /** Returns {@link LanguageRecord} — used by {@code @service} on a {@code Language} return. */
    public static LanguageRecord getLanguage() { throw new UnsupportedOperationException(); }

    /**
     * Returns {@code Result<LanguageRecord>} — used to exercise the inner-generic-mismatch
     * rejection path where a {@code [Film!]!} field expecting {@code Result<FilmRecord>} is
     * pointed at a method whose outer type matches but whose inner type does not.
     */
    public static Result<LanguageRecord> getLanguages() { throw new UnsupportedOperationException(); }

    // ===== Methods that intentionally violate return-type strictness =====

    /**
     * Service method with a {@code List<Row1<Integer>>} parameter and a String return.
     * Used to verify the strict-return-type rejection path; if the method also took a Sources
     * param it would still be the strict-return-type rejection that fires first. For Sources-
     * specific tests use {@link #getFilmWithSources}.
     */
    public static String getWithSources(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== Sources classification tests =====

    /**
     * Takes a {@code List<FilmRecord>} — a jOOQ {@code TableRecord} subtype. Used to verify
     * that a {@code TableRecord} element type classifies as
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Wrap.TableRecord} +
     * {@link no.sikt.graphitron.rewrite.model.LoaderRegistration.Container#POSITIONAL_LIST}
     * (carrying the typed record class on the wrap).
     */
    public static Result<FilmRecord> getFilmsWithTableRecordSources(java.util.List<FilmRecord> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code List<TestDtoStub>} — a plain class with no jOOQ semantics. Used to verify
     * that a non-{@code TableRecord} element type is rejected with a DTO-sources error.
     */
    public static Result<FilmRecord> getFilmsWithDtoSources(java.util.List<TestDtoStub> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code List<Record1<Integer>>} — used to verify that a {@code RecordN} element
     * type classifies as {@link no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record} +
     * {@link no.sikt.graphitron.rewrite.model.LoaderRegistration.Container#POSITIONAL_LIST}.
     */
    public static Result<FilmRecord> getFilmsWithListOfRecord1Sources(java.util.List<org.jooq.Record1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code Set<FilmRecord>} — verifies that a {@code TableRecord} element type with
     * a {@code Set} container classifies as
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Wrap.TableRecord} +
     * {@link no.sikt.graphitron.rewrite.model.LoaderRegistration.Container#MAPPED_SET}
     * (carrying the typed record class on the wrap; drives {@code newMappedDataLoader}).
     */
    public static Result<FilmRecord> getFilmsWithSetOfTableRecordSources(java.util.Set<FilmRecord> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code Set<Row1<Integer>>} — verifies that a {@code RowN} element type with a
     * {@code Set} container classifies as {@link no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Row}
     * + {@link no.sikt.graphitron.rewrite.model.LoaderRegistration.Container#MAPPED_SET}.
     */
    public static Result<FilmRecord> getFilmsWithSetOfRow1Sources(java.util.Set<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code Set<Record1<Integer>>} — verifies that a {@code RecordN} element type with
     * a {@code Set} container classifies as {@link no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record}
     * + {@link no.sikt.graphitron.rewrite.model.LoaderRegistration.Container#MAPPED_SET}.
     */
    public static Result<FilmRecord> getFilmsWithSetOfRecord1Sources(java.util.Set<org.jooq.Record1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code Set<TestDtoStub>} — verifies that a non-{@code TableRecord} element type
     * with a {@code Set} container is rejected with the same DTO-sources error as the
     * {@code List} variant (not the generic "unrecognized sources type" message).
     */
    public static Result<FilmRecord> getFilmsWithSetOfDtoSources(java.util.Set<TestDtoStub> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code Set<FilmActorRecord>} — a composite-PK {@code TableRecord} subtype. Mirrors
     * the consumer-side regelverk_exp.graphqls case where the `@service` source uses a typed
     * record over a multi-column key. Used to verify that composite-PK typed-record sources
     * classify as {@link no.sikt.graphitron.rewrite.model.SourceKey.Wrap.TableRecord} +
     * {@link no.sikt.graphitron.rewrite.model.LoaderRegistration.Container#MAPPED_SET}
     * (carrying the typed record class) rather than collapsing to a {@code Row}-shaped wrap.
     */
    public static java.util.Map<FilmActorRecord, String>
            getFilmActorsCompositeKey(java.util.Set<FilmActorRecord> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== argMapping override on @service / @tableMethod directive (R53) =====

    /**
     * Takes parameters {@code inputs} (plural) and {@code dryRun}. Used by override tests where
     * the GraphQL mutation declares {@code @service(service: {..., argMapping: "inputs: input"})}
     * so the Java parameter name binds to a differently-named GraphQL argument.
     */
    public static TestFilmDetailsDto runWithRenamedInputs(java.util.List<TestDtoStub> inputs, Boolean dryRun) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a String parameter named {@code mode}. Used to verify the argMapping override
     * on an enum-typed GraphQL argument: the GraphQL arg is named {@code direction} but binds
     * to the Java parameter {@code mode}, exercising both the override and the text-enum
     * mapping enrichment in {@code FieldBuilder.enrichArgExtractions}.
     */
    public static TestFilmDetailsDto runWithEnumOverride(String mode) {
        throw new UnsupportedOperationException();
    }

    // ===== R150 service-input-bean fixtures =====

    /**
     * Takes a single {@link TestInputBean}. Used by the R150 classifier test to verify that a
     * scalar bean-typed parameter paired with an SDL input-object argument resolves to
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean}.
     */
    public static TestFilmDetailsDto runWithInputBean(TestInputBean input) {
        throw new UnsupportedOperationException();
    }

    /**
     * R214 fixture: takes a single {@link TestInputBean} parameter whose name does not match
     * any conventional GraphQL argument name. Used to assert the arity-unique inference: when
     * the field declares exactly one argument and the method takes exactly one non-Table /
     * non-DSLContext / non-Context parameter, the pair binds positionally without
     * {@code argMapping}, even when the slot is a named input object whose Java mapping is
     * not resolvable to a canonical scalar type.
     */
    public static String runWithInputBeanRenamed(TestInputBean payload) {
        throw new UnsupportedOperationException();
    }

    /**
     * R214 fixture: single non-Table / non-DSLContext / non-Context {@code String} parameter
     * whose name does not match any conventional GraphQL argument or slot. Used to assert
     * that the type-unique inference yields when an unclaimed sibling slot is a named input
     * object containing a reachable nested field of the same Java type — the binding is
     * then ambiguous (top-level positional + nested dot-path are both reachable), and the
     * inference must defer to name-based matching and let {@link ServiceCatalog#unambiguousReachablePath}
     * surface the dot-path suggestion.
     */
    public static String getByFilmId(String filmId) {
        throw new UnsupportedOperationException();
    }

    /**
     * R214 fixture: single {@link java.math.BigDecimal} parameter — used to assert that the
     * arity-unique gate routes through {@code ctx.types} rather than a hard-coded spec
     * built-in allow-list. With a consumer-defined {@code Decimal -> BigDecimal} scalar
     * registered, a {@code BigDecimal} parameter against a named input object slot defers
     * to the dot-path hint exactly as a {@code String} parameter does.
     */
    public static String getByPrice(java.math.BigDecimal price) {
        throw new UnsupportedOperationException();
    }

    /**
     * R214 fixture: single {@code List<Integer>} parameter whose name does not match any
     * conventional GraphQL argument. Used to assert that the arity-unique branch yields
     * (defers to the dot-path hint) when the lone unclaimed slot is a named input object
     * containing a reachable nested {@code [Int!]!} field of matching Java type — the
     * binding is structurally ambiguous (top-level positional + nested dot-path both reach
     * the same Java type), so inference must not silently bind to the wrapper.
     */
    public static String requestByIds(java.util.List<Integer> requestedIds) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code List<TestInputBean>}. Used by the R150 classifier test to verify that the
     * plural arg shape resolves to a single InputBean extraction (the list-shape is read from
     * the Java type at emit time).
     */
    public static TestFilmDetailsDto runWithInputBeans(java.util.List<TestInputBean> inputs) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@link TestInputBeanWithPrimitive} (record with an {@code int n} component). Used
     * by R155 to verify the primitive→wrapper box at the resolver boundary: the {@code n} field's
     * {@code FieldBinding} must carry {@code javaElementTypeName == "java.lang.Integer"}, not
     * {@code "int"}.
     */
    public static TestFilmDetailsDto runWithInputBeanPrimitive(TestInputBeanWithPrimitive input) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@link TestInputJavaBeanWithBoolean} (JavaBean with a {@code void setActive(boolean)}
     * setter). Used by R155 to verify the primitive→wrapper box on the JavaBean indexing path:
     * the {@code active} field's {@code FieldBinding} must carry {@code javaElementTypeName ==
     * "java.lang.Boolean"}, not {@code "boolean"}.
     */
    public static TestFilmDetailsDto runWithInputJavaBeanBoolean(TestInputJavaBeanWithBoolean input) {
        throw new UnsupportedOperationException();
    }

    // ===== R200 @field(name:) Java-member binding on input beans =====

    /**
     * R200 fixture: takes a {@link TestInputBeanRenamed} record whose component names ({@code heading},
     * {@code score}) diverge from the SDL field names ({@code title}, {@code rating}). The SDL input
     * bridges the divergence with {@code @field(name:)}; the resolved {@code FieldBinding}s must carry
     * {@code javaFieldName} = the component name and {@code sdlFieldName} = the SDL field name. Also
     * the bean for the ambiguity rejection case (two SDL fields colliding on one binding key).
     */
    public static TestFilmDetailsDto runWithRenamedRecord(TestInputBeanRenamed input) {
        throw new UnsupportedOperationException();
    }

    /**
     * R200 fixture: takes a {@link TestInputJavaBeanRenamed} whose setter properties ({@code heading},
     * {@code score}) diverge from the SDL field names, bridged by {@code @field(name:)}. Also the bean
     * for the regression-floor case: an unbridged divergent-name JavaBean (no {@code @field}) must
     * still reject with "has no fields matching", not start matching by coincidence.
     */
    public static TestFilmDetailsDto runWithRenamedJavaBean(TestInputJavaBeanRenamed input) {
        throw new UnsupportedOperationException();
    }

    /**
     * R200 fixture: takes a {@link TestInputSubsetRecord} (two components) against an SDL input with a
     * third field. The extra field binds to no component; R200's direction-B check rejects the field
     * at classify time rather than silently dropping its value before the canonical constructor.
     */
    public static String runWithSubsetRecord(TestInputSubsetRecord input) {
        throw new UnsupportedOperationException();
    }

    // ===== R158 @service carrier-data-field fixtures =====

    /**
     * Returns {@code List<FilmActorRecord>} — used by R158 MANY / composite-PK admission tests.
     * {@code FilmActorRecord} is the typed jOOQ record for the {@code film_actor} junction table
     * with composite PK {@code (actor_id, film_id)}.
     */
    public static java.util.List<FilmActorRecord> getFilmActorsAsList() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code List<LanguageRecord>} — used by R158 wrong-element-type reject tests.
     * Pointed at a carrier whose data field's element table is {@code film}; the
     * service-producer-strict-return check rejects.
     */
    public static java.util.List<LanguageRecord> getLanguagesAsList() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code Set<FilmRecord>} — used by R158 Set / Iterable return reject tests. The
     * service-producer-strict-return check requires exactly {@code List<XRecord>} or
     * {@code XRecord}; {@code Set} is rejected.
     */
    public static java.util.Set<FilmRecord> getFilmsAsSet() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code Iterable<FilmRecord>} — used by R158 Set / Iterable return reject tests.
     */
    public static Iterable<FilmRecord> getFilmsAsIterable() {
        throw new UnsupportedOperationException();
    }

    // ===== ErrorChannel carrier-classifier fixtures (R12 §2c) =====

    /**
     * Returns the dummy {@link no.sikt.graphitron.codereferences.dummyreferences.SakPayload}
     * record, whose canonical constructor exposes one errors-slot parameter
     * ({@code List<Object>}) and one defaulted slot. Used by tests that exercise the carrier
     * classifier's {@code ErrorChannel} resolution on {@code MutationServiceRecordField} /
     * {@code QueryServiceRecordField}.
     */
    public static no.sikt.graphitron.codereferences.dummyreferences.SakPayload runSak() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the dummy {@code SakPayload} from a {@link TestInputBean} input. Used by the
     * validator-pre-step regression test that needs an Input-typed arg (for the R94 fromMap
     * materialisation path) plus a SakPayload return (so the surrounding ErrorChannel resolves
     * as a PayloadClass that the validator pre-step can pre-populate with violations).
     */
    public static no.sikt.graphitron.codereferences.dummyreferences.SakPayload runSakWithInputBean(TestInputBean input) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the multi-ctor variant of the dummy payload. Used by the test that exercises
     * canonical-constructor selection on hand-rolled record-backed POJOs that declare extra
     * constructors alongside the canonical (all-fields) one.
     */
    public static no.sikt.graphitron.codereferences.dummyreferences.MultiCtorSakPayload runMultiCtorSak() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the setter-shape sibling payload. Used by tests exercising R154 §2's mutable-bean
     * payload-construction shape: the service returns the SDL payload type directly (the
     * universal-passthrough path); the carrier classifier resolves the ErrorChannel against the
     * setter-shape payload class.
     */
    public static no.sikt.graphitron.codereferences.dummyreferences.SetterShapeSakPayload runSetterSak() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the both-shapes sibling payload. Used by the canonical-over-bridge precedence
     * test: a class that exposes both an all-fields ctor and a no-arg ctor + setters classifies
     * as AllFieldsCtor (predicate 1 short-circuits).
     */
    public static no.sikt.graphitron.codereferences.dummyreferences.BothShapesSakPayload runBothShapesSak() {
        throw new UnsupportedOperationException();
    }

    /**
     * R178 step 3 SettKvotesporsmal-shape regression: returns the SDL payload class directly
     * (legacy passthrough). The payload class exposes a {@code film()} accessor returning the
     * inner {@code FilmRecord}, but the {@code @service} method's reflected return type is the
     * payload class itself, not the inner record. Before R178 step 3 the carrier walk admitted
     * the payload shape as a single-record carrier and demanded {@code FilmRecord} as the return
     * type; after step 3 the unified path classifies the data field through the standard
     * record-backed parent accessor lookup, with no carrier-walk consultation.
     */
    public static no.sikt.graphitron.codereferences.dummyreferences.SettKvotesporsmalShapePayload runPassthroughPayload() {
        throw new UnsupportedOperationException();
    }

    // ===== R32 child-@service strict-return-type fixtures =====

    /**
     * Child {@code @service} fixture exercising the outer-shape rejection arm of
     * {@code ServiceDirectiveResolver.validateChildServiceReturnType}: a {@code @table}-bound
     * child field returning {@code Language} (single) with a {@code List<Row1<Integer>>}
     * Sources param structurally requires {@code List<LanguageRecord>} per the rows-method
     * shape (post-R177: {@code V = tb.table().recordClass() = LanguageRecord}). This stub
     * declares scalar {@code LanguageRecord} instead of {@code List<LanguageRecord>}, so
     * classification rejects on the outer-shape mismatch (scalar vs {@code List<V>}).
     */
    public static no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord childServiceRowKeyedWrongReturn(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Child {@code @service} fixture exercising the strict-return rejection on the scalar
     * branch: a {@code String}-returning child field with a {@code Set<Record1<Integer>>}
     * Sources param structurally requires {@code Map<Record1<Integer>, String>} per the
     * mapped rows-method shape. This stub declares {@code Map<Record1<Integer>, Integer>}
     * to deliberately mismatch the per-key element type.
     */
    public static java.util.Map<org.jooq.Record1<Integer>, Integer> childServiceMappedRecordKeyedWrongScalarValue(java.util.Set<org.jooq.Record1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== R177 child-@service TableBoundReturnType V-narrowing fixtures =====

    /**
     * R177 migration arm: a list-cardinality {@code @table}-bound child field with a
     * {@code List<Row1<Integer>>} Sources param post-R177 structurally requires
     * {@code List<List<LanguageRecord>>} (V = {@code tb.table().recordClass()}). Pre-R177 it
     * required {@code List<List<Record>>}, so this signature was accepted. Post-R177 the
     * raw {@code Record} on V mismatches the narrowed expectation.
     */
    public static java.util.List<java.util.List<org.jooq.Record>> childServiceRowKeyedRawRecordList(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R177 acceptance arm: the same list-cardinality child field, declaring
     * {@code List<List<LanguageRecord>>}. Pre-R177 this was rejected (expected
     * {@code List<List<Record>>}); post-R177 this is the canonical accepted shape because
     * V = {@code tb.table().recordClass() = LanguageRecord}.
     */
    public static java.util.List<java.util.List<no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord>> childServiceRowKeyedSpecificRecordList(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R177 cross-record regression arm: same list-cardinality {@code Language}-typed child
     * field, but the declared per-key record class is the wrong jOOQ record
     * ({@code FilmRecord} instead of {@code LanguageRecord}). Always rejected by
     * {@code TypeName.equals}: pre-R177 against {@code List<List<Record>>}, post-R177
     * against {@code List<List<LanguageRecord>>}. Pins the diagnostic-wording change to
     * the narrowed expected type without re-litigating the axis.
     */
    public static java.util.List<java.util.List<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>> childServiceRowKeyedWrongRecordList(java.util.List<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    // ===== R12 §4 declared-checked-exception fixtures =====

    /**
     * Declares {@code throws java.sql.SQLException}. Used by classifier tests verifying that
     * {@link ServiceCatalog#reflectServiceMethod} captures declared exceptions onto
     * {@link MethodRef.Basic#declaredExceptions()} and that the §4 match check rejects the
     * field when no covering {@code @error} handler is present on its channel.
     */
    public static String getThrowingSqlException() throws java.sql.SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Declares {@code throws java.io.IOException} — exempt under §4's "Special cases"
     * subsection, so a field whose service method has only this throws clause classifies
     * cleanly even without a covering channel handler.
     */
    public static String getThrowingIoException() throws java.io.IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Declares both {@code SQLException} (non-exempt, requires a handler) and
     * {@code InterruptedException} (exempt). Used to assert that exemption applies per-class
     * and that the unmatched list contains only the non-exempt entries.
     */
    public static String getThrowingSqlAndInterrupted() throws java.sql.SQLException, InterruptedException {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a raw {@code Map<String, Object>} for what the SDL declares as an input-object slot.
     * R150 rejects this shape as an anti-pattern at generation time: the only safe outcome for an
     * input-object SDL arg is a populated typed bean. Legitimate open-ended-JSON use cases route
     * through {@code @scalarType} on a custom scalar instead.
     */
    public static String runWithMapInput(java.util.Map<String, Object> input) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a self-referential record bean. R150 rejects recursive shapes at generation time
     * (the walker would otherwise infinite-loop).
     */
    public static String runWithRecursiveBean(TestInputRecursive input) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a package-private record bean. R150 rejects non-public bean classes at generation
     * time because the generated fetcher lives in a different package and can't reach them.
     */
    public static String runWithPackagePrivateBean(TestInputPackagePrivate input) {
        throw new UnsupportedOperationException();
    }

    /**
     * R195 fixture: takes a {@link TestNodeIdRecordBean} whose member is a jOOQ {@link FilmRecord}.
     * The SDL input-bean field backing that member carries {@code @nodeId(typeName: "Film")}, so the
     * classifier must decode the wire-format id into a {@code FilmRecord} rather than casting the
     * wire {@code String} (the R150/R195 {@code ClassCastException}).
     */
    public static String assignFilm(TestNodeIdRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R195 fixture: takes a {@link TestNodeIdCompositeRecordBean} whose member is a jOOQ
     * {@link FilmActorRecord} backed by a composite-key table ({@code film_actor}, PK
     * {@code (actor_id, film_id)}). The classifier decodes the wire-format id into a
     * {@code FilmActorRecord}, materialising both key columns with one typed {@code set} each.
     */
    public static String assignFilmActor(TestNodeIdCompositeRecordBean in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R195 fixture: takes a {@link TestNodeIdRecordListBean} whose member is a
     * {@code List<FilmRecord>} backed by a {@code [ID!] @nodeId(typeName: "Film")} SDL field. The
     * classifier decodes each wire-format id into a {@code FilmRecord} via the list helper variant.
     */
    public static String assignFilmList(TestNodeIdRecordListBean in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R195 fixture: the both-dimensions corner — a {@code List<FilmActorRecord>} member backed by a
     * {@code [ID!] @nodeId(typeName: "FilmActor")} SDL field, exercising the list variant over a
     * composite-key per-element decode.
     */
    public static String assignFilmActorList(TestNodeIdCompositeRecordListBean in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R195 fixture: takes a {@link TestNodeIdMismatchedRecordBean} whose member is a
     * {@code FilmActorRecord}, while the SDL field carries {@code @nodeId(typeName: "Film")} (whose
     * {@code @table} is {@code film} → {@code FilmRecord}). The record-type / typeName mismatch must
     * be rejected at generation time, not emitted as a decode helper returning the wrong record type.
     */
    public static String assignMismatchedRecord(TestNodeIdMismatchedRecordBean in) {
        throw new UnsupportedOperationException();
    }

    // ===== R311 jOOQ TableRecord @service input param fixtures (root + child coordinate) =====

    /**
     * R311 root singular: a jOOQ {@link FilmRecord} bound directly as a {@code @service} input param
     * (not a bean member). The SDL input names columns through {@code @field(name:)} and carries a
     * {@code @nodeId} identity, so the param binds on the column axis + scalar-key decode — never
     * bean-ified on the Java-member axis. Returns {@code String} so the field is not a record-return
     * (the param-binding is the subject, not the return).
     */
    public static String modifyFilmRecord(FilmRecord in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R311 root list: a {@code List<FilmRecord>} bound directly as a {@code @service} input param —
     * the consumer's motivating shape ({@code endreUtdanningsspesifikasjonsstatus(List<…Record>)}).
     * Differs from the singular only by a {@code ValueShape.ListOf} wrap; the same {@code JooqRecord}
     * carrier drives one shared {@code createFilmRecord} construction site.
     */
    public static String modifyFilmRecords(java.util.List<FilmRecord> in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R311 root composite-key: a jOOQ {@link FilmActorRecord} (composite PK {@code actor_id, film_id})
     * bound directly as a {@code @service} input param; the {@code @nodeId} identity decodes both key
     * columns (arity 2).
     */
    public static String modifyFilmActorRecord(FilmActorRecord in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R311 child coordinate parity: a child {@code @service} rows-method on a {@code @table} parent,
     * taking the parent key (Sources, {@code List<Row1<Integer>>}) plus a {@link FilmRecord} arg. The
     * arg classifies to {@code CallSiteExtraction.JooqRecord} exactly as the root param does, and the
     * child rows-method emits {@code createFilmRecord} through {@code ArgCallEmitter} (the real arm,
     * not a throw) — the binding is coordinate-agnostic because {@code enrich} runs for child
     * {@code @service} too. Returns {@code List<LanguageRecord>} to match a singular {@code Language}
     * child field's rows-method outer shape (post-R177 V = {@code LanguageRecord}).
     */
    public static java.util.List<LanguageRecord> childModifyFilmRecord(
            java.util.List<org.jooq.Row1<Integer>> keys, FilmRecord in) {
        throw new UnsupportedOperationException();
    }

    // ===== R315 FK-reference @nodeId on jOOQ-record @service input param fixtures =====

    /**
     * R315 FK-constraint-not-name-match pin: a {@link FilmEndorsementRecord} whose FK child column
     * ({@code endorsed_film}) is named differently from the referenced parent key ({@code film.film_id}).
     * An FK-reference {@code @nodeId(typeName: "Film")} must resolve the target column through the FK
     * constraint (landing the decoded Film id on {@code endorsed_film}), never a name-match shortcut.
     * Also the mixed identity + FK-reference + plain-{@code @field} fixture (its serial PK
     * {@code endorsement_id} is a nullable same-table identity, its {@code endorsed_film} an FK reference,
     * its {@code note} a plain column).
     */
    public static String modifyFilmEndorsementRecord(FilmEndorsementRecord in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R315 composite / reordered-FK decode-order reconciliation pin (nodeidfixture catalog): a
     * {@code ReorderedFkChildRecord} whose FK references {@code reordered_pk_parent (pk_b, pk_c, pk_a)}
     * while the parent NodeType's key is {@code (pk_a, pk_b, pk_c)}. The decode's target columns must be
     * reconciled to node-key (decode) order {@code [fk_a, fk_b, fk_c]}, not the FK declaration order.
     */
    public static String modifyReorderedFkChild(
            no.sikt.graphitron.rewrite.nodeidfixture.tables.records.ReorderedFkChildRecord in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R315 explicit {@code @reference(key:)} disambiguation pin (idreffixture catalog): a
     * {@code StudierettRecord} whose table carries <em>two</em> FKs to {@code studieprogram}
     * ({@code studieprogram_id} and the renamed {@code registrar_studieprogram}). {@code @reference(key:)}
     * selects which FK an FK-reference {@code @nodeId(typeName: "Studieprogram")} resolves through;
     * omitting it gives the ambiguous-FK rejection.
     */
    public static String modifyStudierett(
            no.sikt.graphitron.rewrite.idreffixture.tables.records.StudierettRecord in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R315 node-key-not-covered rejection pin (nodeidfixture catalog): a {@code ChildRefRecord} whose FK
     * ({@code child_ref.parent_alt_key → parent_node.alt_key}) references the parent's <em>alternate</em>
     * unique column, while the {@code ParentNode} NodeType's key is {@code pk_id}. An FK-reference
     * {@code @nodeId(typeName: "ParentNode")} through that FK leaves {@code pk_id} uncovered, which rejects.
     */
    public static String modifyChildRef(
            no.sikt.graphitron.rewrite.nodeidfixture.tables.records.ChildRefRecord in) {
        throw new UnsupportedOperationException();
    }

    /**
     * R328 self-FK reference pin (public catalog): an {@link EmailRecord} whose self-FK
     * {@code email_in_reply_to_fk (mailbox_id, in_reply_to_no) -> (mailbox_id, message_no)} lets a
     * same-table {@code @nodeId(typeName: "Email") @reference} populate the reply-pointer child columns
     * (never the record's own composite PK). The decoded Email key lands on
     * {@code (mailbox_id, in_reply_to_no)}.
     */
    public static String modifyEmailRecord(
            no.sikt.graphitron.rewrite.test.jooq.tables.records.EmailRecord in) {
        throw new UnsupportedOperationException();
    }
}
