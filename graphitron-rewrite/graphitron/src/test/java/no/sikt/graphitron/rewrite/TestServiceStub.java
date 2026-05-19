package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord;
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
 * field whose resolved return type is {@code @table}-bound (or a {@code @record}
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
    public static String runWithRenamedInputs(java.util.List<TestDtoStub> inputs, Boolean dryRun) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a String parameter named {@code mode}. Used to verify the argMapping override
     * on an enum-typed GraphQL argument: the GraphQL arg is named {@code direction} but binds
     * to the Java parameter {@code mode}, exercising both the override and the text-enum
     * mapping enrichment in {@code FieldBuilder.enrichArgExtractions}.
     */
    public static String runWithEnumOverride(String mode) {
        throw new UnsupportedOperationException();
    }

    // ===== R150 service-input-bean fixtures =====

    /**
     * Takes a single {@link TestInputBean}. Used by the R150 classifier test to verify that a
     * scalar bean-typed parameter paired with an SDL input-object argument resolves to
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean}.
     */
    public static String runWithInputBean(TestInputBean input) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@code List<TestInputBean>}. Used by the R150 classifier test to verify that the
     * plural arg shape resolves to a single InputBean extraction (the list-shape is read from
     * the Java type at emit time).
     */
    public static String runWithInputBeans(java.util.List<TestInputBean> inputs) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@link TestInputBeanWithPrimitive} (record with an {@code int n} component). Used
     * by R155 to verify the primitive→wrapper box at the resolver boundary: the {@code n} field's
     * {@code FieldBinding} must carry {@code javaElementTypeName == "java.lang.Integer"}, not
     * {@code "int"}.
     */
    public static String runWithInputBeanPrimitive(TestInputBeanWithPrimitive input) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes a {@link TestInputJavaBeanWithBoolean} (JavaBean with a {@code void setActive(boolean)}
     * setter). Used by R155 to verify the primitive→wrapper box on the JavaBean indexing path:
     * the {@code active} field's {@code FieldBinding} must carry {@code javaElementTypeName ==
     * "java.lang.Boolean"}, not {@code "boolean"}.
     */
    public static String runWithInputJavaBeanBoolean(TestInputJavaBeanWithBoolean input) {
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
     * canonical-constructor selection on hand-rolled {@code @record} POJOs that declare extra
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
     * {@code @record}-parent accessor lookup, with no carrier-walk consultation.
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
}
