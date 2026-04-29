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

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TypeFetcherGenerator}. Tests verify structural properties of the
 * generated TypeSpec (method names, return types, parameter signatures) — not the generated code
 * body. Code correctness is verified by compiling and executing the generated output in the
 * {@code graphitron-test} module.
 */
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
        var columns = bodyParams.stream()
            .map(bp -> (BodyParam.ColumnEq) bp)
            .map(bp -> new LookupMapping.ColumnMapping.LookupColumn(
                new LookupMapping.ColumnMapping.SourcePath(List.of(bp.name())),
                bp.column(), bp.extraction(), bp.list()))
            .toList();
        return new QueryField.QueryLookupTableField("Query", name, null, returnType,
            List.of(), new OrderBySpec.None(), null,
            new LookupMapping.ColumnMapping(columns, FILM_TABLE));
    }

    private static BodyParam listKeyParam(String name, String javaName, String javaType) {
        return new BodyParam.ColumnEq(name, col(name, javaName, javaType), javaType, false, true,
            new CallSiteExtraction.Direct());
    }

    private static BodyParam scalarKeyParam(String name, String javaName, String javaType) {
        return new BodyParam.ColumnEq(name, col(name, javaName, javaType), javaType, false, false,
            new CallSiteExtraction.Direct());
    }

    private static BodyParam listIdKeyParam(String name, String javaName, String javaType) {
        return new BodyParam.ColumnEq(name, col(name, javaName, javaType), javaType, false, true,
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
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("keys", "env", "sel");
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

    private static GraphitronField mappedServiceField(String parentType, String name, boolean isList, BatchKey batchKey) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = tableBoundFilm(returnWrapper);
        var method = new MethodRef.Basic(
            "no.example.FilmService", "getFilms", ClassName.get("java.util", "Set"),
            List.of(new MethodRef.Param.Sourced("keys", batchKey)));
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method, batchKey);
    }

    private static TypeSpec specWithMappedServiceField(String parentType, String fieldName, boolean isList, BatchKey batchKey) {
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
                "graphql.schema.DataFetchingEnvironment",
                "graphql.schema.SelectedField");
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
            TestFixtures.tableBoundFilm(nonNullList()), method);
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
            new ReturnTypeRef.ScalarReturnType("Int", single()), method);
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
            new ReturnTypeRef.ScalarReturnType("Int", single()), method);
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
            new ReturnTypeRef.ScalarReturnType("Tags", single()), method);
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
            new ReturnTypeRef.ScalarReturnType("Stats", single()), method);
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
            new ReturnTypeRef.ScalarReturnType("Nums", single()), method);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        assertThat(method(spec, "nums").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<? extends java.lang.Number>>");
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
}
