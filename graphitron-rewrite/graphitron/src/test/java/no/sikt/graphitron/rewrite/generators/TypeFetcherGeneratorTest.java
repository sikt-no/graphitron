package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Unit tests for {@link TypeFetcherGenerator}. Tests verify structural properties of the
 * generated TypeSpec (method names, return types, parameter signatures) — not the generated code
 * body. Code correctness is verified by compiling and executing the generated output in the
 * {@code graphitron-test} module.
 */
@UnitTier
class TypeFetcherGeneratorTest {

    private static final TableRef FILM_TABLE = filmTable();

    private static GraphitronField columnField(String name, String columnName, String javaName, String columnClass) {
        return TestFixtures.columnField("Film", name, columnName, javaName, columnClass);
    }

    private static GraphitronField queryTableField(String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = tableBoundFilm(wrapper);
        return new QueryField.QueryTableField("Query", name, null, returnType,
            List.of(), new OrderBySpec.None(), null);
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameIsTypeNamePlusFetchers() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(spec.name()).isEqualTo("FilmFetchers");
    }

    @Test
    void generate_hasNoWiringMethod() {
        // Fetcher registration bodies are emitted by FetcherRegistrationsEmitter, not TypeFetcherGenerator.
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).doesNotContain("wiring");
    }

    // ===== ColumnField with null parentTable → classifier invariant violated (D4) =====

    @Test
    void columnField_nullParentTable_throwsIllegalState() {
        assertThatThrownBy(() ->
            TypeFetcherGenerator.generateTypeSpec("Film", null,
                List.of(columnField("title", "title", "TITLE", "java.lang.String"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("classifier invariant violated");
    }

    // ===== ColumnField with parentTable → wired via ColumnFetcher, no per-field method =====

    @Test
    void columnFetcher_withParentTable_noPerFieldMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .doesNotContain("title");
    }

    @Test
    void columnFetcher_withParentTable_hasNoMethods() {
        // ColumnField wiring is handled by FetcherRegistrationsEmitter; the Fetchers class has no methods.
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).isEmpty();
    }

    // ===== QueryTableField =====

    @Test
    void queryTableField_list_returnsResultRecord() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void queryTableField_single_returnsRecord() {
        var field = queryTableField("film", false);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "film").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

    @Test
    void queryTableField_hasEnvParameter() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    // Dropped queryTableField_isNotStub / queryLookupField_rowsMethod_isNotStub /
    // queryLookupField_scalarKey_isNotStub / connectionField_isNotStub: redundant with
    // GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus's four-way
    // partition — any leaf in IMPLEMENTED_LEAVES or PROJECTED_LEAVES is guaranteed not to
    // route through stub(f).

    // ===== QueryLookupTableField =====

    private static GraphitronField lookupQueryField(String name, List<BodyParam> bodyParams) {
        var returnType = tableBoundFilm(nonNullList());
        // Post-argres Phase 1: @lookupKey args live on LookupMapping, not on filters. Build the
        // mapping from the fixture's "body params" (retaining the parameter name as a fixture
        // convenience — callers still talk in terms of logical arg rows).
        var args = bodyParams.stream()
            .map(bp -> {
                // Test fixture: synthesise a ScalarLookupArg from a single-column body param. The
                // post-(d) shapes are Eq (scalar) and In (list); both expose `column()` via the
                // sealed ColumnPredicate root.
                if (bp instanceof BodyParam.Eq eq) {
                    return (LookupMapping.ColumnMapping.LookupArg) new LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
                        eq.name(), eq.column(), eq.extraction(), false);
                }
                if (bp instanceof BodyParam.In in) {
                    return (LookupMapping.ColumnMapping.LookupArg) new LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
                        in.name(), in.column(), in.extraction(), true);
                }
                throw new IllegalStateException("Unsupported BodyParam shape in test fixture: " + bp.getClass());
            })
            .toList();
        return new QueryField.QueryLookupTableField("Query", name, null, returnType,
            List.of(), new OrderBySpec.None(), null,
            new LookupMapping.ColumnMapping(args, FILM_TABLE));
    }

    private static BodyParam listKeyParam(String name, String javaName, String javaType) {
        return new BodyParam.In(name, col(name, javaName, javaType), javaType, false,
            new CallSiteExtraction.Direct());
    }

    private static BodyParam scalarKeyParam(String name, String javaName, String javaType) {
        return new BodyParam.Eq(name, col(name, javaName, javaType), javaType, false,
            new CallSiteExtraction.Direct());
    }

    private static BodyParam listIdKeyParam(String name, String javaName, String javaType) {
        return new BodyParam.In(name, col(name, javaName, javaType), javaType, false,
            new CallSiteExtraction.JooqConvert(javaName));
    }

    @Test
    void queryLookupField_dataFetcher_returnsResultRecord() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void queryLookupField_dataFetcher_hasEnvParameter() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    // Dropped queryLookupField_dataFetcher_delegatesToRowsMethod: asserted that filmById's body
    // references lookupFilmById. A dangling helper reference would fail compile on
    // graphitron-test; a wrong helper reference would fail execution. The sibling
    // test queryLookupField_hasLookupRowsMethod asserts the helper method exists.

    @Test
    void queryLookupField_hasLookupRowsMethod() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("lookupFilmById");
    }

    @Test
    void queryLookupField_rowsMethod_returnsResultRecord() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "lookupFilmById").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void queryLookupField_rowsMethod_hasEnvParameter() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "lookupFilmById").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    // Dropped queryLookupField_rowsMethod_isNotStub / queryLookupField_scalarKey_isNotStub:
    // see the top-of-class note at queryTableField_isNotStub — partition test covers this.

    @Test
    void queryLookupField_idListKey_bindsViaColumnDataTypeInInputRowsHelper() {
        var field = lookupQueryField("filmById", List.of(listIdKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        // intentional body-content assertion — no structural equivalent.
        // Post argres Phase 1: column type info flows through the input-rows helper via
        // DSL.val(value, table.FILM_ID.getDataType()) — jOOQ's own Converter does the coercion.
        // If the getDataType() call is dropped, GraphQL String args won't coerce to the target
        // column's Java type at bind time. Execution tier covers the end-to-end behaviour but
        // the specific emitter call is tested here for faster diagnosis of regressions inside
        // LookupValuesJoinEmitter.
        assertThat(method(spec, "filmByIdInputRows").code().toString()).contains("getDataType()");
    }

    // ===== @splitQuery TableField =====

    private static final TableRef LANGUAGE_TABLE = languageTableWithPk();

    private static GraphitronField splitQueryField(String parentType, String name) {
        return new ChildField.SplitTableField(parentType, name, null,
            tableBoundFilm(nonNullList()),
            List.of(new no.sikt.graphitron.rewrite.model.JoinStep.FkJoin(
                "film_language_id_fkey", "", LANGUAGE_TABLE, List.of(),
                FILM_TABLE, List.of(), null, name + "_0")),
            List.of(), new OrderBySpec.None(), null,
            new BatchKey.RowKeyed(List.of(languageIdCol())));
    }

    private static TypeSpec specWithSplitQuery(String parentType, String fieldName) {
        return TypeFetcherGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE,
            List.of(splitQueryField(parentType, fieldName)));
    }

    @Test
    void splitQuery_generatesAsyncFetcherAndRowsMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE,
            List.of(splitQueryField("Language", "films")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("films", "rowsFilms");
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
    }

    @Test
    void splitQuery_asyncDataFetcherIsPublicStatic() {
        var m = method(specWithSplitQuery("Language", "films"), "films");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(
            javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void splitQuery_asyncDataFetcherTakesEnv() {
        var m = method(specWithSplitQuery("Language", "films"), "films");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void splitQuery_rowsMethodNameCapitalizesFieldName() {
        assertThat(specWithSplitQuery("Language", "actors").methodSpecs())
            .extracting(MethodSpec::name)
            .contains("rowsActors");
    }

    @Test
    void splitQuery_rowsMethodIsPublicStatic() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(
            javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void splitQuery_rowsMethodTakesTypedKeyListAndEnv() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<org.jooq.Row1<java.lang.Integer>>",
                "graphql.schema.DataFetchingEnvironment");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("keys", "env");
    }

    @Test
    void splitQuery_rowsMethodReturnsListOfListOfRecord() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
    }

    // ===== @service field with TableBoundReturnType =====

    private static GraphitronField serviceField(String parentType, String name, boolean isList) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = tableBoundFilm(returnWrapper);
        var method = new MethodRef.Basic(
            "no.example.FilmService", "getFilms", ClassName.get("java.util", "List"),
            List.of(
                new MethodRef.Param.Sourced("keys",
                    new BatchKey.RowKeyed(List.of(languageIdCol()))),
                new MethodRef.Param.Typed("filter", "java.lang.String", new ParamSource.Arg(new CallSiteExtraction.Direct(), "filter")),
                new MethodRef.Param.Typed("tenantId", "java.lang.String", new ParamSource.Context())
            )
        );
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method,
            new BatchKey.RowKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer"))));
    }

    private static TypeSpec specWithServiceField(String parentType, String fieldName, boolean isList) {
        return TypeFetcherGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE,
            List.of(serviceField(parentType, fieldName, isList)));
    }

    @Test
    void serviceField_list_dataFetcherReturnsCompletableFutureListRecord() {
        assertThat(method(specWithServiceField("Language", "films", true), "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
    }

    @Test
    void serviceField_single_dataFetcherReturnsCompletableFutureRecord() {
        assertThat(method(specWithServiceField("Language", "film", false), "film").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<org.jooq.Record>>");
    }

    @Test
    void serviceField_list_rowsMethodSignature() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("keys", "env");
    }

    @Test
    void serviceField_dataFetcherCallsNewDataLoader_notWithContext() {
        // Regression: DataLoaderFactory has no `newDataLoaderWithContext` method; the lambda
        // shape `(keys, batchEnv) -> ...` already binds to BatchLoaderWithContext through plain
        // `newDataLoader(...)`. The split-query path was correct; the service path used to call
        // a non-existent method (caught only at compile-tier).
        var body = method(specWithServiceField("Language", "films", true), "films").code().toString();
        assertThat(body).contains("newDataLoader(");
        assertThat(body).doesNotContain("newDataLoaderWithContext");
        assertThat(body).doesNotContain("newMappedDataLoader");
    }

    // ===== @service field with mapped (Set<...>) batch key =====

    private static GraphitronField mappedServiceField(String parentType, String name, boolean isList, BatchKey.ParentKeyed batchKey) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = tableBoundFilm(returnWrapper);
        var method = new MethodRef.Basic(
            "no.example.FilmService", "getFilms", ClassName.get("java.util", "Set"),
            List.of(new MethodRef.Param.Sourced("keys", batchKey)));
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method, batchKey);
    }

    private static TypeSpec specWithMappedServiceField(String parentType, String fieldName, boolean isList, BatchKey.ParentKeyed batchKey) {
        return TypeFetcherGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE,
            List.of(mappedServiceField(parentType, fieldName, isList, batchKey)));
    }

    private static BatchKey.MappedRowKeyed mappedRowKey() {
        return new BatchKey.MappedRowKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")));
    }

    private static BatchKey.MappedRecordKeyed mappedRecordKey() {
        return new BatchKey.MappedRecordKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")));
    }

    @Test
    void serviceField_mappedRow_list_dataFetcherCallsNewMappedDataLoaderWithSetKeys() {
        var body = method(specWithMappedServiceField("Language", "films", true, mappedRowKey()), "films").code().toString();
        assertThat(body).contains("newMappedDataLoader(");
        assertThat(body).doesNotContain("newDataLoaderWithContext");
        assertThat(body).contains("java.util.Set<org.jooq.Row1<java.lang.Integer>> keys");
    }

    @Test
    void serviceField_mappedRow_list_dataFetcherReturnsCompletableFutureListRecord() {
        assertThat(method(specWithMappedServiceField("Language", "films", true, mappedRowKey()), "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
    }

    @Test
    void serviceField_mappedRow_single_dataFetcherReturnsCompletableFutureRecord() {
        // Mapped vs positional only changes the rows-method return shape (Map vs List); the
        // data fetcher's return is always CompletableFuture<DataFetcherResult<V>> (R12 §3) because
        // loader.load(key, env) returns a per-key promise that the wrapper lifts into the
        // DataFetcherResult envelope.
        assertThat(method(specWithMappedServiceField("Language", "film", false, mappedRowKey()), "film").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<org.jooq.Record>>");
    }

    @Test
    void serviceField_mappedRow_list_rowsMethodTakesSetAndReturnsMap() {
        var m = method(specWithMappedServiceField("Language", "films", true, mappedRowKey()), "loadFilms");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.Set<org.jooq.Row1<java.lang.Integer>>",
                "graphql.schema.DataFetchingEnvironment");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.Map<org.jooq.Row1<java.lang.Integer>, java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_mappedRow_single_rowsMethodReturnsScalarMap() {
        var m = method(specWithMappedServiceField("Language", "film", false, mappedRowKey()), "loadFilm");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.Map<org.jooq.Row1<java.lang.Integer>, org.jooq.Record>");
    }

    @Test
    void serviceField_mappedRecord_list_keyTypeIsRecordN() {
        var m = method(specWithMappedServiceField("Language", "films", true, mappedRecordKey()), "loadFilms");
        assertThat(m.parameters().get(0).type().toString())
            .isEqualTo("java.util.Set<org.jooq.Record1<java.lang.Integer>>");
        var fetcherBody = method(specWithMappedServiceField("Language", "films", true, mappedRecordKey()), "films").code().toString();
        // Record-shape extracts via record.into(...) rather than DSL.row(...).
        assertThat(fetcherBody).contains(".into(");
    }

    // ===== QueryTableField with OrderBySpec.Argument → orderBy helper method =====

    private static QueryField.QueryTableField queryTableFieldWithOrderByArg(String fieldName) {
        var filmIdCol = TestFixtures.filmIdCol();
        var base = new OrderBySpec.Fixed(
            List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null)), "ASC");
        var namedOrder = new OrderBySpec.NamedOrder(
            "TITLE",
            new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null)), "ASC"));
        var orderBy = new OrderBySpec.Argument(
            "order", "FilmOrder", false, false, "field", "direction",
            List.of(namedOrder), base);
        return new QueryField.QueryTableField("Query", fieldName, null,
            TestFixtures.tableBoundFilm(TestFixtures.nonNullList()),
            List.of(), orderBy, null);
    }

    @Test
    void orderByArg_emitsHelperMethod() {
        var field = queryTableFieldWithOrderByArg("films");
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("filmsOrderBy");
    }

    @Test
    void orderByArg_helperMethod_isPrivateStatic() {
        var field = queryTableFieldWithOrderByArg("films");
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        var m = method(spec, "filmsOrderBy");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(
            javax.lang.model.element.Modifier.PRIVATE, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void orderByArg_helperMethod_returnsOrderByResult() {
        var field = queryTableFieldWithOrderByArg("films");
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmsOrderBy").returnType().toString())
            .endsWith("OrderByResult");
    }

    // Dropped orderByArg_helperMethod_bodyConstructsOrderByResult: the sibling test
    // orderByArg_helperMethod_returnsOrderByResult asserts the return type ends with
    // OrderByResult. Java's type system forces the body to construct one — the body-content
    // check was redundant with the return-type check.

    @Test
    void orderByArg_helperMethod_takesEnvAndAliasedTableParameters() {
        // Helper signature: (DataFetchingEnvironment env, <FilmTable> film). The Table is a
        // parameter (not a local declaration) so the same helper serves root callers (pass the
        // canonical tableLocal) and Split+Connection callers (pass the FK-chain terminal alias).
        // See plan-split-query-connection.md §2.
        var field = queryTableFieldWithOrderByArg("films");
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        assertThat(method(spec, "filmsOrderBy").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingEnvironment",
                "no.sikt.graphitron.rewrite.test.jooq.tables.Film");
    }

    // Dropped orderByArg_fetcherBody_callsHelperMethod: Pattern 2 — "method A references
    // helper B" is compile-tier territory. Dangling reference fails test-spec compile; wrong
    // reference fails execution. Test was tied to the emitter's literal "filmsOrderBy(env)"
    // string, breaking on any refactor that changed call-site shape.

    @Test
    void noOrderByArg_noHelperMethod() {
        var field = queryTableField("films", true); // OrderBySpec.None
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).doesNotContain("filmsOrderBy");
    }

    // ===== QueryTableField with FieldWrapper.Connection → connection fetcher =====

    private static PaginationSpec forwardPagination() {
        return new PaginationSpec(
            new PaginationSpec.PaginationArg("Int", false),
            null,
            new PaginationSpec.PaginationArg("String", false),
            null);
    }

    private static QueryField.QueryTableField connectionField(String name) {
        var orderBy = new OrderBySpec.Fixed(
            List.of(new OrderBySpec.ColumnOrderEntry(TestFixtures.filmIdCol(), null)), "ASC");
        return new QueryField.QueryTableField("Query", name, null,
            TestFixtures.tableBoundFilm(new FieldWrapper.Connection(true, 100)),
            List.of(), orderBy, forwardPagination());
    }

    private static QueryField.QueryTableField connectionFieldWithArgOrderBy(String name) {
        var filmIdCol = TestFixtures.filmIdCol();
        var base = new OrderBySpec.Fixed(
            List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null)), "ASC");
        var namedOrder = new OrderBySpec.NamedOrder(
            "TITLE",
            new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null)), "ASC"));
        var orderBy = new OrderBySpec.Argument(
            "order", "FilmOrder", false, false, "field", "direction",
            List.of(namedOrder), base);
        return new QueryField.QueryTableField("Query", name, null,
            TestFixtures.tableBoundFilm(new FieldWrapper.Connection(true, 100)),
            List.of(), orderBy, forwardPagination());
    }

    @Test
    void connectionField_returnsConnectionResult() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(connectionField("films")));
        // Wrapped in DataFetcherResult<...> per R12 §3.
        assertThat(method(spec, "films").returnType().toString()).endsWith("ConnectionResult>");
    }

    // Dropped connectionField_isNotStub: see queryTableField_isNotStub — partition test covers it.

    // Dropped connectionField_usesPaginationArgNamesFromModel: Pattern 5 — the default
    // pagination arg names (first/after/last/before) are exercised end-to-end by the 18+
    // filmsConnection cases in graphitron-test's GraphQLQueryTest.

    @Test
    void connectionField_withOrderByArg_emitsHelperMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null,
            List.of(connectionFieldWithArgOrderBy("films")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("filmsOrderBy");
    }

    // Dropped connectionField_withOrderByArg_fetcherCallsHelper: Pattern 2 — helper-reference
    // body assertion, covered by compile tier. Sibling test above asserts the helper exists.

    // Dropped connectionField_customPaginationArgNames_emittedInFetcher: custom pagination arg
    // names are no longer supported. The classifier (FieldBuilder.isPaginationArg) only accepts
    // first/last/after/before, so the slot fixes the name and PaginationArg no longer carries one.

    // ===== Backward pagination and Relay validation =====

    // Dropped connectionField_emitsLastAndBeforeArgReads: Pattern 5 — backward-pagination
    // execution covered by filmsConnection_backward_returnsLastNFilms and
    // filmsConnection_backward_withBeforeCursor_returnsPrevPage.

    // Dropped connectionField_emitsRelayValidation_firstAndLastConflict: execution test
    // GraphQLQueryTest.filmsConnection_rejectsFirstAndLastTogether covers this behaviourally
    // (passes first=2 + last=2 and asserts the error surfaces with both arg names in the
    // message).

    // Dropped connectionField_emitsBackwardFlag: Pattern 5 — backward semantics exercised by
    // the filmsConnection_backward_* execution tests.

    // connectionField_emitsReverseOrderByHelper + siblings removed: fetcher-quality §1
    // (commit 1900453) moved reverseOrderBy emission into ConnectionHelper.pageRequest, so
    // the TypeFetcherGenerator no longer emits a reverseOrderBy helper on the fetcher class.
    // The underlying behaviour is exercised by the filmsConnection_backward_* execution tests
    // in graphitron-test.

    // Dropped connectionField_usesSingleExpressionSeek and connectionField_columnDrivenCursorDecode:
    // Pattern 5 — cursor decode + seek semantics exercised end-to-end by
    // filmsConnection_withAfterCursor_returnsNextPage and
    // filmsConnection_backward_withBeforeCursor_returnsPrevPage. Any regression in the seek
    // or decodeCursor shape breaks those queries.

    @Test
    void connectionField_withOrderByArg_extraFieldsComeFromOrderingResult() {
        // intentional body-content assertion — no structural equivalent.
        // Both orderBy (for SQL) and extraFields (for cursor) must be derived from the same
        // OrderByResult dispatch — the same "ordering" local. If they diverge, SQL ORDER BY
        // and cursor columns get out of sync, which execution-tier only catches on a specific
        // multi-column ordering query with cursor pagination. Keep the body assertion as a
        // faster diagnostic signal.
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null,
            List.of(connectionFieldWithArgOrderBy("films")));
        var code = method(spec, "films").code().toString();
        assertThat(code).contains("ordering.sortFields()");
        assertThat(code).contains("ordering.columns()");
    }

    // ===== Service / tableMethod root fetchers =====

    @Test
    void queryTableMethodTableField_emittedFetcher_declaresSpecificTableLocalAndProjects() {
        // Structural-tier assertions only: method exists, signature is (DataFetchingEnvironment env),
        // return type is Result<Record> for a List-cardinality @table-bound return. Body-shape
        // properties (specific-table local, $fields projection, .from(table) call) are behavioural
        // and asserted at execution tier — see GraphQLQueryTest.queryTableMethod_popularFilms_*.
        var method = new MethodRef.Basic(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "popularFilms",
            ClassName.get("no.sikt.graphitron.rewrite.test.jooq.tables", "Film"),
            List.of(
                new MethodRef.Param.Typed("filmTable",
                    "no.sikt.graphitron.rewrite.test.jooq.tables.Film",
                    new ParamSource.Table()),
                new MethodRef.Param.Typed("minRentalRate", "java.lang.Double",
                    new ParamSource.Arg(new CallSiteExtraction.Direct(), "minRentalRate"))));
        var field = new QueryField.QueryTableMethodTableField("Query", "popularFilms", null,
            TestFixtures.tableBoundFilm(nonNullList()), method);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        var fetcher = method(spec, "popularFilms");
        assertThat(fetcher.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
        assertThat(fetcher.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void queryServiceTableField_emittedFetcher_declaresTypedResult() {
        // Structural-tier assertion: List-cardinality @table-bound @service field returns
        // Result<FilmRecord> typed, not Object. Body-shape properties (the optional dsl local,
        // direct service call, no projection) are behavioural and asserted at execution tier —
        // see GraphQLQueryTest.queryServiceTable_filmsByService_*.
        var method = new MethodRef.Basic(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "filmsByService",
            ParameterizedTypeName.get(
                ClassName.get("org.jooq", "Result"),
                ClassName.get("no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord")),
            List.of(
                new MethodRef.Param.Typed("dsl", "org.jooq.DSLContext", new ParamSource.DslContext()),
                new MethodRef.Param.Typed("ids", "java.util.List<java.lang.Integer>",
                    new ParamSource.Arg(new CallSiteExtraction.Direct(), "ids"))));
        var field = new QueryField.QueryServiceTableField("Query", "filmsByService", null,
            TestFixtures.tableBoundFilm(nonNullList()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        assertThat(method(spec, "filmsByService").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_declaresScalarReturnFromMethodReflection() {
        // Structural-tier assertion: ScalarReturnType faithfully reflects the developer's
        // declared return type — no widening to Object. Behavioural round-trip
        // (graphql-java coercing Integer to GraphQL Int!) is asserted at execution tier —
        // see GraphQLQueryTest.queryServiceRecord_filmCount_*.
        var method = new MethodRef.Basic(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "filmCount",
            ClassName.get("java.lang", "Integer"),
            List.of(new MethodRef.Param.Typed("dsl", "org.jooq.DSLContext", new ParamSource.DslContext())));
        var field = new QueryField.QueryServiceRecordField("Query", "filmCount", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        // R12 §3 wraps every fetcher's return in DataFetcherResult<P>; ScalarReturnType still
        // surfaces the developer's reflected return type as the inner P.
        assertThat(method(spec, "filmCount").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.Integer>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesPrimitiveReturnType() {
        // Reflection of `int filmCount()` produces returnTypeName "int". The emitter must
        // declare the primitive faithfully on the inner P slot — boxing to Integer only
        // happens because DataFetcherResult<P> requires a reference type for P, and the
        // primitive int boxes to Integer per R12 §3.
        var method = new MethodRef.Basic(
            "com.example.Service", "filmCount", TypeName.INT, List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "filmCount", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        assertThat(method(spec, "filmCount").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.Integer>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesArrayReturnType() {
        // Reflection of `String[] tags()` produces returnTypeName "java.lang.String[]". The
        // emitter must preserve the array shape faithfully via ArrayTypeName.
        var method = new MethodRef.Basic(
            "com.example.Service", "tags",
            ArrayTypeName.of(ClassName.get("java.lang", "String")), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "tags", null,
            new ReturnTypeRef.ScalarReturnType("Tags", single()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        assertThat(method(spec, "tags").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.String[]>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesMultiArgGenericReturnType() {
        // Reflection of `Map<String, Integer> stats()` produces a multi-arg type name. The
        // emitter's depth-aware comma splitter must yield ParameterizedTypeName with two args.
        var method = new MethodRef.Basic(
            "com.example.Service", "stats",
            ParameterizedTypeName.get(
                ClassName.get("java.util", "Map"),
                ClassName.get("java.lang", "String"),
                ClassName.get("java.lang", "Integer")), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "stats", null,
            new ReturnTypeRef.ScalarReturnType("Stats", single()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        assertThat(method(spec, "stats").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.Map<java.lang.String, java.lang.Integer>>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesBoundedWildcardReturnType() {
        // Reflection of `List<? extends Number> nums()` produces "java.util.List<? extends
        // java.lang.Number>". The emitter must preserve the wildcard via WildcardTypeName.
        var method = new MethodRef.Basic(
            "com.example.Service", "nums",
            ParameterizedTypeName.get(
                ClassName.get("java.util", "List"),
                WildcardTypeName.subtypeOf(ClassName.get("java.lang", "Number"))), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "nums", null,
            new ReturnTypeRef.ScalarReturnType("Nums", single()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        assertThat(method(spec, "nums").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<? extends java.lang.Number>>");
    }

    // ===== R12 §3 try/catch wrapper: dispatch arm vs redact arm =====

    private static ErrorChannel sakPayloadChannel() {
        // Mirrors the SakPayload(String data, List<?> errors) shape used by
        // ErrorChannelClassificationTest. The mappedErrorTypes list is empty here because the
        // catch-arm emission only walks errorsSlotIndex + defaultedSlots + mappingsConstantName;
        // the channel's @error classes don't need to be resolved for the lambda print.
        return new ErrorChannel(
            List.of(),
            ClassName.bestGuess("com.example.SakPayload"),
            1,
            List.of(
                new no.sikt.graphitron.rewrite.model.DefaultedSlot(
                    0, "data", ClassName.get("java.lang", "String"), "null")),
            "SAK_PAYLOAD");
    }

    @Test
    void queryServiceRecordField_withErrorChannel_catchArmDispatchesThroughErrorRouter() {
        // R12 §3: when the field's WithErrorChannel resolves to a present channel, the catch
        // arm calls ErrorRouter.dispatch with the channel's mapping-table constant and a
        // synthesized payload-factory lambda. No-channel fields still route through redact —
        // covered by every existing service-record test (all pass Optional.empty()).
        var method = new MethodRef.Basic(
            "no.sikt.graphitron.rewrite.TestServiceStub", "runSak",
            ClassName.bestGuess("com.example.SakPayload"), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "sak", null,
            new ReturnTypeRef.ScalarReturnType("SakPayload", single()), method,
            Optional.of(sakPayloadChannel()));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        var body = method(spec, "sak").code().toString();
        assertThat(body).contains("ErrorRouter.dispatch");
        assertThat(body).contains("ErrorMappings.SAK_PAYLOAD");
        // Synthesized payload factory: errors-slot binds the lambda parameter, defaulted slot
        // prints its literal. SakPayload's ctor is (data, errors), so "null, errors" — order
        // mirrors payloadCtorParams.
        assertThat(body).contains("errors -> new com.example.SakPayload(null, errors)");
        assertThat(body).doesNotContain("ErrorRouter.redact");
    }

    @Test
    void queryServiceRecordField_withoutErrorChannel_catchArmStillRedacts() {
        // Counter-test: an absent channel keeps the redact disposition. Same fetcher shape as
        // the dispatch test above but with Optional.empty() for the channel.
        var method = new MethodRef.Basic(
            "com.example.Service", "filmCount", ClassName.get("java.lang", "Integer"), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "filmCount", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()), method, Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        var body = method(spec, "filmCount").code().toString();
        assertThat(body).contains("ErrorRouter.redact(e, env)");
        assertThat(body).doesNotContain("ErrorRouter.dispatch");
        assertThat(body).doesNotContain("ErrorMappings");
    }

    @Test
    void queryServiceTableField_withErrorChannel_catchArmDispatchesThroughErrorRouter() {
        // Same dispatch wiring applies on the @table-bound service path
        // (buildQueryServiceTableFetcher → buildServiceFetcherCommon shared body shape).
        var method = new MethodRef.Basic(
            "no.sikt.graphitron.rewrite.TestServiceStub", "getFilm",
            ClassName.get("no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord"),
            List.of());
        var field = new QueryField.QueryServiceTableField("Query", "getFilm", null,
            tableBoundFilm(single()), method, Optional.of(sakPayloadChannel()));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        var body = method(spec, "getFilm").code().toString();
        assertThat(body).contains("ErrorRouter.dispatch");
        assertThat(body).contains("ErrorMappings.SAK_PAYLOAD");
        assertThat(body).doesNotContain("ErrorRouter.redact");
    }

    // ===== QueryTableInterfaceField =====

    private static QueryField.QueryTableInterfaceField queryTableInterfaceField(String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = tableBoundFilm(wrapper);
        return new QueryField.QueryTableInterfaceField("Query", name, null, returnType,
            "FILM_TYPE", List.of("FILM", "SHORT"), List.of(),
            List.of(), new OrderBySpec.None(), null);
    }

    @Test
    void queryTableInterfaceField_list_returnsResultRecord() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "allContent").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void queryTableInterfaceField_single_returnsRecord() {
        var field = queryTableInterfaceField("content", false);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "content").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

    @Test
    void queryTableInterfaceField_hasEnvParameter() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "allContent").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void queryTableInterfaceField_isPublicStatic() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "allContent").modifiers())
            .containsExactlyInAnyOrder(
                javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void queryTableInterfaceField_discriminatorFilter_appearsInBody() {
        // Intentional body-content assertion: no structural equivalent for the IN-filter.
        // The discriminator filter restricts to known concrete types; if dropped, queries
        // silently return rows of unknown type that the TypeResolver cannot route.
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        var code = method(spec, "allContent").code().toString();
        assertThat(code).contains("\"FILM\"");
        assertThat(code).contains("\"SHORT\"");
        assertThat(code).contains(".in(");
    }

    @Test
    void queryTableInterfaceField_emptyKnownValues_noInFilter() {
        // When no discriminator values are known, the filter must not be emitted.
        var returnType = tableBoundFilm(nonNullList());
        var field = new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "FILM_TYPE", List.of(), List.of(), List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        var code = method(spec, "allContent").code().toString();
        assertThat(code).doesNotContain(".in(");
    }

    @Test
    void queryTableInterfaceField_noAsterisk_inSelectClause() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "allContent").code().toString()).doesNotContain("asterisk()");
    }

    @Test
    void queryTableInterfaceField_discriminatorAlwaysSelected() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "allContent").code().toString()).contains("\"FILM_TYPE\"");
    }

    @Test
    void queryTableInterfaceField_participants_emitFieldsCalls() {
        var returnType = tableBoundFilm(nonNullList());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM"),
            new ParticipantRef.TableBound("ShortContent", filmTable(), "SHORT"));
        var field = new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "content_type", List.of("FILM", "SHORT"), participants,
            List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "allContent").code().toString();
        assertThat(code).contains("FilmContent.$fields(");
        assertThat(code).contains("ShortContent.$fields(");
    }

    // ===== TableInterfaceField =====

    private static ChildField.TableInterfaceField tableInterfaceField(String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = tableBoundFilm(wrapper);
        // Fixture: parent (Language) holds the FK → child (Film) PK.
        // FkJoin: source=Film(language_id), target=Language(language_id).
        List<JoinStep> joinPath = List.of(new JoinStep.FkJoin(
            "film_language_id_fkey", "", LANGUAGE_TABLE,
            List.of(languageIdCol()), FILM_TABLE, List.of(languageIdCol()), null, name + "_0"));
        return new ChildField.TableInterfaceField("Language", name, null, returnType,
            "FILM_TYPE", List.of("FILM", "SHORT"), List.of(),
            joinPath, List.of(), new OrderBySpec.None(), null);
    }

    @Test
    void tableInterfaceField_list_returnsResultRecord() {
        var field = tableInterfaceField("content", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        assertThat(method(spec, "content").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void tableInterfaceField_single_returnsRecord() {
        var field = tableInterfaceField("content", false);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        assertThat(method(spec, "content").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

    @Test
    void tableInterfaceField_hasEnvParameter() {
        var field = tableInterfaceField("content", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        assertThat(method(spec, "content").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void tableInterfaceField_discriminatorFilter_appearsInBody() {
        // Intentional body-content assertion: mirrors queryTableInterfaceField test above.
        var field = tableInterfaceField("content", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        var code = method(spec, "content").code().toString();
        assertThat(code).contains("\"FILM\"");
        assertThat(code).contains("\"SHORT\"");
        assertThat(code).contains(".in(");
    }

    @Test
    void tableInterfaceField_emptyKnownValues_noInFilter() {
        var returnType = tableBoundFilm(nonNullList());
        List<JoinStep> joinPath = List.of(new JoinStep.FkJoin(
            "film_language_id_fkey", "", LANGUAGE_TABLE,
            List.of(languageIdCol()), FILM_TABLE, List.of(languageIdCol()), null, "content_0"));
        var field = new ChildField.TableInterfaceField("Language", "content", null, returnType,
            "FILM_TYPE", List.of(), List.of(), joinPath, List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        var code = method(spec, "content").code().toString();
        assertThat(code).doesNotContain(".in(");
    }

    @Test
    void tableInterfaceField_noAsterisk_inSelectClause() {
        var field = tableInterfaceField("content", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        assertThat(method(spec, "content").code().toString()).doesNotContain("asterisk()");
    }

    @Test
    void tableInterfaceField_discriminatorAlwaysSelected() {
        var field = tableInterfaceField("content", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
        assertThat(method(spec, "content").code().toString()).contains("\"FILM_TYPE\"");
    }

    @Test
    void tableInterfaceField_participants_emitFieldsCalls() {
        var returnType = tableBoundFilm(nonNullList());
        List<JoinStep> joinPath = List.of(new JoinStep.FkJoin(
            "film_language_id_fkey", "", LANGUAGE_TABLE,
            List.of(languageIdCol()), FILM_TABLE, List.of(languageIdCol()), null, "content_0"));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM"),
            new ParticipantRef.TableBound("ShortContent", filmTable(), "SHORT"));
        var field = new ChildField.TableInterfaceField("Language", "content", null, returnType,
            "content_type", List.of("FILM", "SHORT"), participants,
            joinPath, List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "content").code().toString();
        assertThat(code).contains("FilmContent.$fields(");
        assertThat(code).contains("ShortContent.$fields(");
    }

    // ===== Cross-table participant fields =====
    //
    // The interface fetcher emits a conditional LEFT JOIN per cross-table participant field. The
    // gating uses the graphql-java type-scoped selection-set API (Type.field); the JOIN's ON
    // clause includes the participant's discriminator equality so non-matching rows project NULL
    // for the cross-table column rather than spuriously matching every row.

    private static ParticipantRef.TableBound.CrossTableField filmContentRatingCrossTable() {
        var ratingCol = new ColumnRef("rating", "RATING", "java.lang.String");
        var contentToFilmFk = new JoinStep.FkJoin(
            "content_film_id_fkey", "", joinTarget("content"),
            List.of(filmIdCol()),       // FK on content (sourceColumns)
            filmTableWithPk(),          // film (targetTable)
            List.of(filmIdCol()),       // film.film_id (targetColumns)
            null, "rating_0");
        return new ParticipantRef.TableBound.CrossTableField(
            "rating", ratingCol, contentToFilmFk, "FilmContent_rating");
    }

    @Test
    void queryTableInterfaceField_crossTableField_emitsTypeScopedSelectionGuard() {
        var returnType = tableBoundFilm(nonNullList());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM",
                List.of(filmContentRatingCrossTable())),
            new ParticipantRef.TableBound("ShortContent", filmTable(), "SHORT"));
        var field = new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "content_type", List.of("FILM", "SHORT"), participants,
            List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "allContent").code().toString();
        assertThat(code)
            .as("type-scoped selection-set check gates per-participant cross-table column fetch")
            .contains("env.getSelectionSet().contains(\"FilmContent.rating\")");
    }

    @Test
    void queryTableInterfaceField_crossTableField_emitsLeftJoinWithDiscriminatorGate() {
        var returnType = tableBoundFilm(nonNullList());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM",
                List.of(filmContentRatingCrossTable())));
        var field = new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "content_type", List.of("FILM"), participants,
            List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "allContent").code().toString();
        assertThat(code)
            .as("LEFT JOIN to the cross table is gated by the alias-presence check")
            .contains("step = step.leftJoin(FilmContent_rating_alias).on(");
        assertThat(code)
            .as("ON clause includes the FK equality (target.eq(source))")
            .contains("FilmContent_rating_alias.FILM_ID.eq(filmTable.FILM_ID)");
        assertThat(code)
            .as("ON clause includes the participant's discriminator value so non-matching rows NULL")
            .contains("eq(\"FILM\")");
    }

    @Test
    void queryTableInterfaceField_crossTableField_aliasedColumnAddedToSelect() {
        var returnType = tableBoundFilm(nonNullList());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM",
                List.of(filmContentRatingCrossTable())));
        var field = new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "content_type", List.of("FILM"), participants,
            List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "allContent").code().toString();
        assertThat(code)
            .as("cross-table column is projected with the alias so the per-field DataFetcher reads it back by name")
            .contains("fields.add(FilmContent_rating_alias.RATING.as(\"FilmContent_rating\"))");
    }

    @Test
    void queryTableInterfaceField_noCrossTableFields_noLeftJoinEmitted() {
        var returnType = tableBoundFilm(nonNullList());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM"),
            new ParticipantRef.TableBound("ShortContent", filmTable(), "SHORT"));
        var field = new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "content_type", List.of("FILM", "SHORT"), participants,
            List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "allContent").code().toString();
        assertThat(code)
            .as("no LEFT JOIN when no participant declares cross-table fields")
            .doesNotContain(".leftJoin(");
    }

    @Test
    void tableInterfaceField_crossTableField_emitsLeftJoinAtChildSite() {
        // Both interface fetcher entry points (Query- and ChildField-rooted) share
        // buildCrossTableAliasDeclarations + buildCrossTableJoinChain; this asserts the
        // emission applies at the child site too.
        var returnType = tableBoundFilm(nonNullList());
        List<JoinStep> joinPath = List.of(new JoinStep.FkJoin(
            "film_language_id_fkey", "", LANGUAGE_TABLE,
            List.of(languageIdCol()), FILM_TABLE, List.of(languageIdCol()), null, "content_0"));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM",
                List.of(filmContentRatingCrossTable())));
        var field = new ChildField.TableInterfaceField("Language", "content", null, returnType,
            "content_type", List.of("FILM"), participants,
            joinPath, List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var code = method(spec, "content").code().toString();
        assertThat(code).contains("env.getSelectionSet().contains(\"FilmContent.rating\")");
        assertThat(code).contains("step = step.leftJoin(FilmContent_rating_alias).on(");
    }

    @Test
    void graphitronContextHelper_targetsLocallyEmittedInterfaceByClassKey() {
        // Pins the commit that retargeted GraphitronContext from no.sikt.graphql to the
        // generated <outputPackage>.schema package, and the key from the string
        // "graphitronContext" to the typed GraphitronContext.class lookup.
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(queryTableField("film", false)), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var helper = method(spec, "graphitronContext");
        var expectedFqn = DEFAULT_OUTPUT_PACKAGE + ".schema.GraphitronContext";
        assertThat(helper.returnType().toString())
            .as("retargeted to the locally-emitted interface under the output package")
            .isEqualTo(expectedFqn);
        assertThat(helper.code().toString())
            .as("keys on the typed class, not a string")
            .contains("env.getGraphQlContext().get(" + expectedFqn + ".class)");
    }

    // ===== R49: ServiceRecordField (scalar / @record-backed return) =====
    //
    // ServiceRecordField shares the DataLoader emitters with ServiceTableField; the only axis
    // of variation is the per-key value type (perKeyType): RECORD for table-bound,
    // field.elementType() for record-bound. These tests assert that the parameterisation
    // surfaces the correct loader signature, factory selection, and rows-method shape for
    // each variant.

    private static no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField scalarServiceRecordField(
            String parentType, String name, boolean isList, BatchKey.ParentKeyed batchKey, no.sikt.graphitron.javapoet.TypeName perKeyType) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = new no.sikt.graphitron.rewrite.model.ReturnTypeRef.ScalarReturnType("String", returnWrapper);
        var method = new MethodRef.Basic(
            "no.example.Service", "getValues", perKeyType,
            List.of(new MethodRef.Param.Sourced("keys", batchKey)));
        return new no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField(
            parentType, name, null, returnType, List.of(), method, batchKey);
    }

    private static no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField recordBackedServiceRecordField(
            String parentType, String name, boolean isList, BatchKey.ParentKeyed batchKey, String fqBackingClass) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = new no.sikt.graphitron.rewrite.model.ReturnTypeRef.ResultReturnType(
            "FilmDetails", returnWrapper, fqBackingClass);
        var method = new MethodRef.Basic(
            "no.example.Service", "getDetails", ClassName.bestGuess(fqBackingClass),
            List.of(new MethodRef.Param.Sourced("keys", batchKey)));
        return new no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField(
            parentType, name, null, returnType, List.of(), method, batchKey);
    }

    private static TypeSpec specWith(GraphitronField field) {
        return TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
    }

    @Test
    void serviceRecordField_scalar_single_loaderValueIsPerKeyType() {
        var field = scalarServiceRecordField(
            "Language", "displayName", false,
            new BatchKey.RowKeyed(List.of(languageIdCol())),
            ClassName.get(String.class));
        assertThat(method(specWith(field), "displayName").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.lang.String>>");
    }

    @Test
    void serviceRecordField_scalar_list_loaderValueIsListOfPerKeyType() {
        var field = scalarServiceRecordField(
            "Language", "displayNames", true,
            new BatchKey.RowKeyed(List.of(languageIdCol())),
            ClassName.get(String.class));
        assertThat(method(specWith(field), "displayNames").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<java.lang.String>>>");
    }

    @Test
    void serviceRecordField_recordBacked_single_loaderValueIsBackingClass() {
        var field = recordBackedServiceRecordField(
            "Language", "details", false,
            new BatchKey.RowKeyed(List.of(languageIdCol())),
            "no.example.FilmDetails");
        assertThat(method(specWith(field), "details").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<no.example.FilmDetails>>");
    }

    @Test
    void serviceRecordField_mappedRow_list_dataFetcherCallsNewMappedDataLoaderWithSetKeys() {
        var field = scalarServiceRecordField(
            "Language", "displayNames", true,
            new BatchKey.MappedRowKeyed(List.of(languageIdCol())),
            ClassName.get(String.class));
        var body = method(specWith(field), "displayNames").code().toString();
        assertThat(body).contains("newMappedDataLoader(");
        assertThat(body).doesNotContain("newDataLoaderWithContext");
    }

    @Test
    void serviceRecordField_mappedRow_list_rowsMethodReturnsMapToListOfElement() {
        var field = scalarServiceRecordField(
            "Language", "displayNames", true,
            new BatchKey.MappedRowKeyed(List.of(languageIdCol())),
            ClassName.get(String.class));
        // rowsMethodName follows ServiceTableField's "load<Pascal>" convention.
        var rows = method(specWith(field), "loadDisplayNames");
        assertThat(rows.returnType().toString())
            .isEqualTo("java.util.Map<org.jooq.Row1<java.lang.Integer>, java.util.List<java.lang.String>>");
    }

    @Test
    void serviceRecordField_positional_single_rowsMethodReturnsListOfElement() {
        var field = scalarServiceRecordField(
            "Language", "displayName", false,
            new BatchKey.RowKeyed(List.of(languageIdCol())),
            ClassName.get(String.class));
        // Positional + single-cardinality field: rows-method returns List<V>, ordered by input
        // key index (ServiceTableField shape parity).
        var rows = method(specWith(field), "loadDisplayName");
        assertThat(rows.returnType().toString())
            .isEqualTo("java.util.List<java.lang.String>");
    }

    // ===== R36 Track B2: QueryInterfaceField / QueryUnionField (multi-table polymorphic) =====
    //
    // Two-stage emission: Stage 1 narrow UNION ALL projecting (typename, pk, sort) per branch.
    // Stage 2 per-typename batched lookup using the post-R55 ValuesJoinRowBuilder primitive
    // with the dispatcher-shape .on(...) join. Result records carry __typename so the
    // schema-class TypeResolver routes each row to its concrete GraphQL type.

    private static QueryField.QueryInterfaceField queryInterfaceField(String name, boolean isList,
                                                                       List<ParticipantRef> participants) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Searchable", wrapper);
        return new QueryField.QueryInterfaceField("Query", name, null, returnType, participants);
    }

    private static QueryField.QueryUnionField queryUnionField(String name, boolean isList,
                                                               List<ParticipantRef> participants) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Document", wrapper);
        return new QueryField.QueryUnionField("Query", name, null, returnType, participants);
    }

    private static List<ParticipantRef> filmAndActorParticipants() {
        return List.of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                new TableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
    }

    @Test
    void queryInterfaceField_list_returnsListOfRecord() {
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        assertThat(method(spec, "search").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>");
    }

    @Test
    void queryInterfaceField_single_returnsRecord() {
        var field = queryInterfaceField("documentById", false, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        assertThat(method(spec, "documentById").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

    @Test
    void queryInterfaceField_emitsTwoStageStructure() {
        // Stage 1: narrow UNION ALL projecting (__typename, __pk0__, __sort__) per branch.
        // Stage 2: per-typename dispatch into select<Participant>For<Field> helpers.
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "search").code().toString();
        assertThat(body)
            .as("stage 1: narrow UNION ALL of per-branch projections")
            .contains(".unionAll(");
        assertThat(body)
            .as("stage 1: synthetic typename literal")
            .contains("\"__typename\"");
        assertThat(body)
            .as("stage 1: PK projection alias")
            .contains("\"__pk0__\"");
        assertThat(body)
            .as("stage 1: ordered by sort key")
            .contains("\"__sort__\"");
        assertThat(body)
            .as("stage 2 dispatch into per-typename helpers")
            .contains("selectFilmForSearch(")
            .contains("selectActorForSearch(");
    }

    @Test
    void queryInterfaceField_stage1ProjectsTypenameAndPksPerBranch() {
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "search").code().toString();
        assertThat(body)
            .as("each branch projects DSL.inline(\"<Type>\")")
            .contains("inline(\"Film\")")
            .contains("inline(\"Actor\")");
        assertThat(body)
            .as("each branch projects its PK column aliased to __pk0__")
            .contains("FILM_ID.as(\"__pk0__\")")
            .contains("ACTOR_ID.as(\"__pk0__\")");
    }

    @Test
    void queryInterfaceField_perTypenameHelpersExist_andCallParticipantFields() {
        // Stage 2 invokes <Type>.$fields(env.getSelectionSet(), t, env) for the typed projection.
        // Selection-set narrowing works at full strength only with $fields, not asterisk().
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var film = method(spec, "selectFilmForSearch");
        var actor = method(spec, "selectActorForSearch");
        assertThat(film.code().toString()).contains("Film.$fields(env.getSelectionSet(), t, env)");
        assertThat(actor.code().toString()).contains("Actor.$fields(env.getSelectionSet(), t, env)");
    }

    @Test
    void queryInterfaceField_perTypenameHelpers_useDispatcherShapeOnNotUsing() {
        // R55 reviewer pivot: dispatcher uses .on(...) not .using(...) because the SELECT
        // projection includes <Type>.$fields(...) which references t.<col> directly.
        // USING would collapse joined columns and risk colliding with $fields-emitted projections.
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "selectFilmForSearch").code().toString();
        assertThat(body)
            .as("dispatcher shape uses .on(...) for the values-derived join")
            .contains(".join(input).on(");
        assertThat(body)
            .as("does not use .using(...) — would collapse t.<col> with $fields projections")
            .doesNotContain(".join(input).using(");
    }

    @Test
    void queryInterfaceField_perTypenameHelpers_addTypenameLiteralToSelect() {
        // Each typed Record carries the synthetic __typename column so the schema-class
        // TypeResolver (registered by GraphitronSchemaClassGenerator under R36 B1) routes
        // each row back to its concrete GraphQL type.
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "selectFilmForSearch").code().toString();
        assertThat(body).contains("inline(\"Film\")")
            .contains(".as(\"__typename\")");
    }

    @Test
    void queryInterfaceField_perTypenameHelpers_arePrivateStatic() {
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        assertThat(method(spec, "selectFilmForSearch").modifiers())
            .containsExactlyInAnyOrder(
                javax.lang.model.element.Modifier.PRIVATE,
                javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void queryInterfaceField_compositePkParticipant_emitsJsonbArraySortKey() {
        // R36 plan: composite-key sort projects DSL.jsonbArray(pk1, pk2, ...).as("__sort__").
        // JSONB arrays compare element-wise in PostgreSQL, so composite ordering reduces to a
        // single comparable column at no extra Java cost.
        var compositeTable = new TableRef("bar", "BAR", "Bar",
            List.of(
                new ColumnRef("id_1", "ID_1", "java.lang.Integer"),
                new ColumnRef("id_2", "ID_2", "java.lang.Integer")));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Bar", compositeTable, null));
        var field = queryInterfaceField("compositeSearch", true, participants);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "compositeSearch").code().toString();
        assertThat(body)
            .as("composite PK sort key uses jsonbArray(...)")
            .contains("jsonbArray(")
            .contains(".as(\"__sort__\")");
    }

    @Test
    void queryUnionField_emitsTwoStageStructure_likeInterfaceField() {
        // QueryUnionField shares MultiTablePolymorphicEmitter with QueryInterfaceField; the
        // emitted bodies are identical apart from the participant-list source. This pin
        // ratchets the equivalence so a future refactor that drifts one without the other
        // fails fast.
        var field = queryUnionField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "search").code().toString();
        assertThat(body).contains(".unionAll(");
        assertThat(body).contains("\"__typename\"");
        assertThat(body).contains("selectFilmForSearch(")
            .contains("selectActorForSearch(");
    }

    @Test
    void queryInterfaceField_isImplementedLeaf_notInNotImplementedReasons() {
        // R36 Track B2 lifts QueryInterfaceField and QueryUnionField from
        // NOT_IMPLEMENTED_REASONS to IMPLEMENTED_LEAVES; the partition test guards the
        // disjoint partition invariant, this asserts membership directly so a regression in
        // the lift surfaces here too.
        assertThat(TypeFetcherGenerator.IMPLEMENTED_LEAVES)
            .contains(QueryField.QueryInterfaceField.class, QueryField.QueryUnionField.class);
        assertThat(TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS)
            .doesNotContainKeys(QueryField.QueryInterfaceField.class, QueryField.QueryUnionField.class);
    }

    // ===== R36 Track B4a: connection pagination on QueryInterfaceField / QueryUnionField =====
    //
    // The connection emit path mirrors the list path but: (a) returns
    // DataFetcherResult<ConnectionResult>, (b) wraps stage 1's UNION ALL in a derived table
    // 'pages' so .orderBy/.seek/.limit apply uniformly across the union, (c) calls
    // ConnectionHelper.pageRequest to derive PageRequest, (d) appends __typename ASC as a
    // secondary sort + cursor tiebreaker so rows with tied sort keys order deterministically,
    // and (e) per-typename stage 2 helpers project the participant PK aliased as __sort__
    // so ConnectionHelper.encodeCursor can read the sort key off each typed Record.

    private static QueryField.QueryInterfaceField queryInterfaceConnectionField(String name,
                                                                                 List<ParticipantRef> participants,
                                                                                 int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Searchable", wrapper);
        return new QueryField.QueryInterfaceField("Query", name, null, returnType, participants);
    }

    private static QueryField.QueryUnionField queryUnionConnectionField(String name,
                                                                        List<ParticipantRef> participants,
                                                                        int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Document", wrapper);
        return new QueryField.QueryUnionField("Query", name, null, returnType, participants);
    }

    @Test
    void queryInterfaceField_connection_returnsDataFetcherResultOfConnectionResult() {
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        assertThat(method(spec, "searchConnection").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<" + DEFAULT_OUTPUT_PACKAGE
                + ".util.ConnectionResult>");
    }

    @Test
    void queryInterfaceField_connection_callsConnectionHelperPageRequest() {
        // ConnectionHelper.pageRequest derives PageRequest (limit, effectiveOrderBy, seekFields,
        // selectFields) from (first, last, after, before, defaultPageSize, orderBy, extraFields,
        // selection). The default page size threads from FieldWrapper.Connection.defaultPageSize().
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body).contains("ConnectionHelper.pageRequest(first, last, after, before, 5,");
    }

    @Test
    void queryInterfaceField_connection_stage1WrapsUnionAllAsDerivedTable() {
        // Stage 1 in connection mode wraps the per-branch UNION ALL in .asTable("pages") so the
        // outer query can apply seek/limit uniformly. The list path emits a flat
        // dsl.select(...).from(...).unionAll(...).orderBy(...).fetch() chain instead; this test
        // pins the divergence.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body).contains(".asTable(\"pages\")");
        assertThat(body).contains(".unionAll(");
    }

    @Test
    void queryInterfaceField_connection_appliesSeekAndLimit() {
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body)
            .contains(".orderBy(page.effectiveOrderBy())")
            .contains(".seek(page.seekFields())")
            .contains(".limit(page.limit())");
    }

    @Test
    void queryInterfaceField_connection_perTypenameHelperProjectsSortKey() {
        // Each typed stage-2 Record carries the participant PK aliased as __sort__ so
        // ConnectionHelper.encodeCursor (which reads the orderByColumns by Field<?> identity)
        // finds the sort key on each row when emitting per-edge cursors and pageInfo
        // start/endCursor.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var film = method(spec, "selectFilmForSearchConnection").code().toString();
        assertThat(film)
            .as("Film stage-2 helper aliases its PK as __sort__")
            .contains("FILM_ID.as(\"__sort__\")");
        var actor = method(spec, "selectActorForSearchConnection").code().toString();
        assertThat(actor)
            .as("Actor stage-2 helper aliases its PK as __sort__")
            .contains("ACTOR_ID.as(\"__sort__\")");
    }

    @Test
    void queryInterfaceField_connection_wrapsResultInConnectionResult() {
        // ConnectionResult takes (List<Record>, PageRequest, Table<?>, Condition). B4b binds
        // (payload, page, pagesTable, DSL.noCondition()) so ConnectionHelper.totalCount can run
        // SELECT count(*) FROM (UNION ALL) AS pages lazily on selection. Per-branch WHEREs (when
        // wired for child connections) live inside the union, so the outer condition is a no-op.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body).contains(".util.ConnectionResult(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())");
    }

    @Test
    void queryInterfaceField_connection_liftsUnionAllAsTableLocal() {
        // B4b lifts the UNION-ALL derived table to a local Table<?> pagesTable variable so the
        // same reference backs both the page query (.from(pagesTable)) and ConnectionResult.table()
        // for totalCount. Without the local, totalCount would have to re-emit the entire
        // UNION ALL, doubling the emission size.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body)
            .as("UNION-ALL is materialized as a local Table<?>")
            .contains("Table<?> pagesTable")
            .contains(".asTable(\"pages\")");
        assertThat(body)
            .as("the page query references the local pagesTable")
            .contains(".from(pagesTable)");
    }

    @Test
    void queryInterfaceField_connection_addsTypenameTiebreakerToOrderBy() {
        // Without a tiebreaker, two rows with the same __sort__ value (e.g. Film(1) and Actor(1))
        // resolve in undefined order and pagination at a tie boundary can double-count or skip.
        // The emitter appends __typename ASC as a secondary ordering and cursor seek field.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body)
            .as("orderBy combines sort key + typename tiebreaker")
            .contains("List.of(sortField.asc(), tieField.asc())");
        assertThat(body)
            .as("extraFields drives both cursor encoding and seek; both columns appear")
            .contains("of(sortField, tieField)");
    }

    @Test
    void queryUnionField_connection_emitsSameShapeAsInterfaceField() {
        // Union variant parity: same emitter, same body shape. Ratchet against drift between
        // QueryInterfaceField and QueryUnionField on the connection path the same way the
        // list path does in queryUnionField_emitsTwoStageStructure_likeInterfaceField.
        var field = queryUnionConnectionField("documentsConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "documentsConnection").code().toString();
        assertThat(body)
            .contains(".asTable(\"pages\")")
            .contains("ConnectionHelper.pageRequest(")
            .contains(".seek(page.seekFields())")
            .contains(".util.ConnectionResult(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())");
        assertThat(method(spec, "selectFilmForDocumentsConnection").code().toString())
            .contains("FILM_ID.as(\"__sort__\")");
    }

    // ===== R36 Track B3: ChildField.InterfaceField / ChildField.UnionField (multi-table polymorphic child) =====
    //
    // Same two-stage emission as B2's root case; differs by an additional per-branch
    // WHERE <participant>.<fk> = parentRecord.<parent_pk> derived from each participant's
    // auto-discovered FK back to the parent table. The fetcher opens with
    // Record parentRecord = (Record) env.getSource() to read parent-side PK values.

    private static java.util.Map<String, List<JoinStep>> filmActorChildJoinPaths() {
        // film_actor → film via film_actor_film_id_fkey: source columns sit on film_actor side.
        // film_actor → actor via film_actor_actor_id_fkey: same shape.
        var filmActorTable = new TableRef("film_actor", "FILM_ACTOR", "FilmActor",
            List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
                    new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")));
        var filmTable = filmTableWithPk();
        var actorTable = new TableRef("actor", "ACTOR", "Actor",
            List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")));
        // FK direction: film_actor (source/FK holder) → film (target/PK side) and similarly for actor.
        var filmFk = new JoinStep.FkJoin("film_actor_film_id_fkey", "",
            filmActorTable, List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
            filmTable, List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")), null, "related_0");
        var actorFk = new JoinStep.FkJoin("film_actor_actor_id_fkey", "",
            filmActorTable, List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")),
            actorTable, List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")), null, "related_1");
        return java.util.Map.of(
            "Film", List.<JoinStep>of(filmFk),
            "Actor", List.<JoinStep>of(actorFk));
    }

    private static ChildField.InterfaceField childInterfaceField(String parentType, String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                new TableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        return new ChildField.InterfaceField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths());
    }

    private static ChildField.UnionField childUnionField(String parentType, String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                new TableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        return new ChildField.UnionField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths());
    }

    @Test
    void childInterfaceField_emitsParentRecordReadFromEnvSource() {
        // R36 plan B3: child fetcher reads the parent jOOQ Record from env.getSource() so each
        // stage-1 branch can reference parent PK values in its WHERE predicate.
        var field = childInterfaceField("FilmActor", "related", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "related").code().toString();
        assertThat(body)
            .as("child fetcher reads parent Record from env.getSource()")
            .contains("parentRecord = (org.jooq.Record) env.getSource()");
    }

    @Test
    void childInterfaceField_emitsParentFkConditionPerBranch() {
        // R36 plan B3 acceptance test: each branch of the stage-1 UNION ALL carries its own
        // .where(participant.<fk> = parentRecord.<parent_pk>) predicate.
        var field = childInterfaceField("FilmActor", "related", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "related").code().toString();
        assertThat(body)
            .as("Film branch's WHERE references stage1 alias and typed parentRecord film_id read")
            .contains("stage1_Film.FILM_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"film_id\"), java.lang.Integer.class))");
        assertThat(body)
            .as("Actor branch's WHERE references stage1 alias and typed parentRecord actor_id read")
            .contains("stage1_Actor.ACTOR_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"actor_id\"), java.lang.Integer.class))");
    }

    @Test
    void childUnionField_emitsSameTwoStageStructureAsInterfaceField() {
        // ChildField.UnionField shares MultiTablePolymorphicEmitter with ChildField.InterfaceField;
        // body shape is identical apart from the participant-list source. Pin the equivalence so
        // a future drift in either path fails fast.
        var field = childUnionField("FilmActor", "related", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "related").code().toString();
        assertThat(body).contains("parentRecord = (org.jooq.Record) env.getSource()");
        assertThat(body).contains(".unionAll(");
        assertThat(body).contains("stage1_Film.FILM_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"film_id\"), java.lang.Integer.class))");
        assertThat(body).contains("stage1_Actor.ACTOR_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"actor_id\"), java.lang.Integer.class))");
        assertThat(body).contains("selectFilmForRelated(")
            .contains("selectActorForRelated(");
    }

    @Test
    void childInterfaceField_isImplementedLeaf_notInNotImplementedReasons() {
        // R36 Track B3 lifts ChildField.InterfaceField and ChildField.UnionField from
        // NOT_IMPLEMENTED_REASONS into IMPLEMENTED_LEAVES.
        assertThat(TypeFetcherGenerator.IMPLEMENTED_LEAVES)
            .contains(ChildField.InterfaceField.class, ChildField.UnionField.class);
        assertThat(TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS)
            .doesNotContainKeys(ChildField.InterfaceField.class, ChildField.UnionField.class);
    }

    // ===== R36 Track B4c-1: child-case connection pagination on ChildField.InterfaceField / UnionField =====
    //
    // Combines B3's per-branch parent-FK WHERE with B4a/B4b's connection-mode emission. Each
    // parent invocation runs its own polymorphic UNION ALL with .seek/.limit and a totalCount
    // bound to the same pagesTable derived table; the count therefore returns only this
    // parent's occupants. DataLoader-batched windowed CTE form is the B4c-2 follow-up.

    private static ChildField.InterfaceField childInterfaceConnectionField(
            String parentType, String name, int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                new TableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        return new ChildField.InterfaceField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths());
    }

    private static ChildField.UnionField childUnionConnectionField(
            String parentType, String name, int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                new TableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        return new ChildField.UnionField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths());
    }

    @Test
    void childInterfaceField_connection_emitsParentRecordReadFromEnvSource() {
        // B4c-1: child connection fetcher reads parentRecord from env.getSource() so each
        // stage-1 branch can scope its WHERE to the carrier parent's PK.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "relatedConnection").code().toString();
        assertThat(body).contains("parentRecord = (org.jooq.Record) env.getSource()");
    }

    @Test
    void childInterfaceField_connection_appliesParentFkWherePerBranch() {
        // Each branch of the UNION ALL carries .where(<participant>.<fk> =
        // parentRecord.get(DSL.name("<parent_pk>"), <Type>.class)). Both branches must scope
        // independently — without per-branch WHERE the union would return cross-product noise.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "relatedConnection").code().toString();
        assertThat(body)
            .as("Film branch's WHERE references parentRecord film_id read")
            .contains("stage1_Film.FILM_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"film_id\"), java.lang.Integer.class))");
        assertThat(body)
            .as("Actor branch's WHERE references parentRecord actor_id read")
            .contains("stage1_Actor.ACTOR_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"actor_id\"), java.lang.Integer.class))");
    }

    @Test
    void childInterfaceField_connection_inheritsConnectionScaffolding() {
        // The child connection path reuses the B4a/B4b scaffolding: pagesTable derived table,
        // ConnectionHelper.pageRequest, .seek/.limit, ConnectionResult carrier with the
        // pagesTable and DSL.noCondition() so totalCount runs over the parent-scoped union.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "relatedConnection").code().toString();
        assertThat(body)
            .contains("ConnectionHelper.pageRequest(first, last, after, before, 5,")
            .contains("Table<?> pagesTable")
            .contains(".asTable(\"pages\")")
            .contains(".from(pagesTable)")
            .contains(".seek(page.seekFields())")
            .contains(".limit(page.limit())")
            .contains(".util.ConnectionResult(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())");
    }

    @Test
    void childUnionField_connection_emitsSameShapeAsChildInterface() {
        // Union variant parity: same emitter, same body shape under the connection path.
        var field = childUnionConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            new TableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of()),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var body = method(spec, "relatedConnection").code().toString();
        assertThat(body)
            .contains("parentRecord = (org.jooq.Record) env.getSource()")
            .contains("stage1_Film.FILM_ID.eq(parentRecord.get(org.jooq.impl.DSL.name(\"film_id\"), java.lang.Integer.class))")
            .contains(".asTable(\"pages\")")
            .contains(".util.ConnectionResult(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())");
    }
}
