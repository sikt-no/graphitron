package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.ArgumentRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.LookupResolution;
import no.sikt.graphitron.rewrite.model.RoutineResolution;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.SqlDialectFamily;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParticipantCorrelation;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Unit tests for {@link TypeFetcherGenerator}. Tests verify structural properties of the
 * generated TypeSpec (method names, return types, parameter signatures), not the generated code
 * body. Code correctness is verified by compiling and executing the generated output in the
 * {@code graphitron-sakila-example} module.
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
            List.of(), new OrderBySpec.None(), null, LookupResolution.None.INSTANCE, RoutineResolution.None.INSTANCE);
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

    // ===== ColumnField with parentTable → reified into a named source-only read method =====

    @Test
    void columnFetcher_withParentTable_reifiesPerFieldMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .contains("title");
    }

    @Test
    void columnFetcher_withParentTable_reifiesExactlyTheReadMethod() {
        // The column read is the only method on the class; the registration wraps the
        // reference in LightFetcher (asserted at the pipeline tier).
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).containsExactly("title");
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

    // No per-variant isNotStub tests here:
    // GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus's four-way partition
    // guarantees any leaf in IMPLEMENTED_LEAVES or the producer-derived projected bucket does not route through stub(f).

    // ===== the lookup-keyed QueryTableField =====

    private static GraphitronField lookupQueryField(String name, List<BodyParam> bodyParams) {
        var returnType = tableBoundFilm(nonNullList());
        // @lookupKey args live on LookupMapping; the fixture synthesises each ScalarLookupArg
        // from a single-column BodyParam row.
        var args = bodyParams.stream()
            .map(bp -> {
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
        return new QueryField.QueryTableField("Query", name, null, returnType,
            List.of(), new OrderBySpec.None(), null,
            new no.sikt.graphitron.rewrite.model.LookupResolution.Keyed(
                new LookupMapping.ColumnMapping(args, FILM_TABLE)), RoutineResolution.None.INSTANCE);
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
    void queryLookupField_rowsMethod_takesTheEntryAcquiredDslAndEnv() {
        // The lookup launcher joined the seam's contract: the entry point owns connection
        // acquisition (one divination, at the entry) and the unit takes the resolved dsl.
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "lookupFilmById").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("org.jooq.DSLContext", "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void queryLookupField_idListKey_bindsViaColumnDataTypeInInputRowsHelper() {
        var field = lookupQueryField("filmById", List.of(listIdKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        // Intentional body-content assertion; no structural equivalent. The input-rows helper
        // binds via DSL.val(value, table.FILM_ID.getDataType()) so jOOQ's own Converter coerces
        // GraphQL String args to the column's Java type at bind time. Execution tier covers the
        // end-to-end behaviour; the emitter call is asserted here for faster diagnosis of
        // regressions inside LookupRows.
        assertThat(method(spec, "filmByIdInputRows").code().toString()).contains("getDataType()");
    }

    // ===== @splitQuery TableField =====

    private static final TableRef LANGUAGE_TABLE = languageTableWithPk();

    private static GraphitronField splitQueryField(String parentType, String name) {
        var rt = tableBoundFilm(nonNullList());
        var keyCols = List.of(languageIdCol());
        var path = List.<no.sikt.graphitron.rewrite.model.JoinStep>of(TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("film_language_id_fkey"), LANGUAGE_TABLE, List.of(languageIdCol()),
            FILM_TABLE, List.of(languageIdCol()), null, name + "_0"));
        return new ChildField.BatchedTableField(parentType, name, null,
            rt,
            path,
            List.of(), new OrderBySpec.None(), null,
            no.sikt.graphitron.rewrite.model.SourceShape.Table,
            TestFixtures.splitSourceKey(keyCols),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(rt, false, false), LookupResolution.None.INSTANCE,
            TestFixtures.pcFor(path, LANGUAGE_TABLE));
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
    // ChildField.ServiceTableField lifts the service result back through a $project-projecting
    // identity re-projection, so the DataLoader value (and rows-method per-key value) is the
    // projected org.jooq.Record carrying the multiset columns, not the developer-returned XRecord.

    private static GraphitronField serviceField(String parentType, String name, boolean isList) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = tableBoundFilm(returnWrapper);
        var keyCols = List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer"));
        var wrap = new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Row();
        var method = TestFixtures.staticServiceMethodRef(
            "no.example.FilmService", "getFilms", ClassName.get("java.util", "List"),
            List.of(
                TestFixtures.sourced("keys", wrap, keyCols, no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.POSITIONAL_LIST),
                new MethodRef.Param.Typed("filter", "java.lang.String", new ParamSource.Arg(new CallSiteExtraction.Direct(), no.sikt.graphitron.rewrite.PathExpr.head("filter"))),
                new MethodRef.Param.Typed("tenantId", "java.lang.String", new ParamSource.Context())
            )
        );
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method,
            TestFixtures.serviceSourceKey(wrap, keyCols),
            TestFixtures.loaderRegistration(returnType, false, false),
            java.util.Optional.empty());
    }

    private static TypeSpec specWithServiceField(String parentType, String fieldName, boolean isList) {
        return TypeFetcherGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE,
            List.of(serviceField(parentType, fieldName, isList)));
    }

    @Test
    void serviceField_list_dataFetcherReturnsCompletableFutureListProjectedRecord() {
        assertThat(method(specWithServiceField("Language", "films", true), "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
    }

    @Test
    void serviceField_single_dataFetcherReturnsCompletableFutureProjectedRecord() {
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
        // `newDataLoader(...)`. A wrong factory name is otherwise caught only at compile tier.
        var body = method(specWithServiceField("Language", "films", true), "films").code().toString();
        assertThat(body).contains("newDataLoader(");
        assertThat(body).doesNotContain("newDataLoaderWithContext");
        assertThat(body).doesNotContain("newMappedDataLoader");
    }

    // ===== @service field with mapped (Set<...>) batch key =====

    /**
     * Source-shape triple the mapped-service fixtures pass through:
     * {@code (Wrap, columns, mapped:boolean)}. {@code mappedRowKey} / {@code mappedRecordKey}
     * below build the two shape variants the tests exercise.
     */
    private record ServiceSourceShape(
            no.sikt.graphitron.rewrite.model.SourceKey.Wrap wrap,
            List<ColumnRef> columns,
            boolean mapped) {}

    private static GraphitronField mappedServiceField(String parentType, String name, boolean isList, ServiceSourceShape shape) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = tableBoundFilm(returnWrapper);
        var container = shape.mapped()
            ? no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.MAPPED_SET
            : no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.POSITIONAL_LIST;
        var method = TestFixtures.staticServiceMethodRef(
            "no.example.FilmService", "getFilms", ClassName.get("java.util", "Set"),
            List.of(TestFixtures.sourced("keys", shape.wrap(), shape.columns(), container)));
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method,
            TestFixtures.serviceSourceKey(shape.wrap(), shape.columns()),
            TestFixtures.loaderRegistration(returnType, shape.mapped(), false),
            java.util.Optional.empty());
    }

    private static TypeSpec specWithMappedServiceField(String parentType, String fieldName, boolean isList, ServiceSourceShape shape) {
        return TypeFetcherGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE,
            List.of(mappedServiceField(parentType, fieldName, isList, shape)));
    }

    private static ServiceSourceShape mappedRowKey() {
        return new ServiceSourceShape(new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Row(),
            List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")), true);
    }

    private static ServiceSourceShape mappedRecordKey() {
        return new ServiceSourceShape(new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Record(),
            List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")), true);
    }

    @Test
    void serviceField_mappedRow_list_dataFetcherCallsNewMappedDataLoaderWithSetKeys() {
        var body = method(specWithMappedServiceField("Language", "films", true, mappedRowKey()), "films").code().toString();
        assertThat(body).contains("newMappedDataLoader(");
        assertThat(body).doesNotContain("newDataLoaderWithContext");
        assertThat(body).contains("java.util.Set<org.jooq.Row1<java.lang.Integer>> keys");
    }

    @Test
    void serviceField_mappedRow_list_dataFetcherReturnsCompletableFutureListProjectedRecord() {
        assertThat(method(specWithMappedServiceField("Language", "films", true, mappedRowKey()), "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
    }

    @Test
    void serviceField_mappedRow_single_dataFetcherReturnsCompletableFutureProjectedRecord() {
        // Mapped vs positional only changes the rows-method return shape (Map vs List); the
        // data fetcher's return is always CompletableFuture<DataFetcherResult<V>> because
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
    void serviceField_mappedRow_single_rowsMethodReturnsSingleRecordMap() {
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
            List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null, OrderBySpec.SortDirection.ASC)), true);
        var namedOrder = new OrderBySpec.NamedOrder(
            "TITLE",
            new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null, OrderBySpec.SortDirection.ASC)), true));
        var orderBy = new OrderBySpec.Argument(
            "order", "FilmOrder", false, false, "field", "direction",
            List.of(namedOrder), base);
        return new QueryField.QueryTableField("Query", fieldName, null,
            TestFixtures.tableBoundFilm(TestFixtures.nonNullList()),
            List.of(), orderBy, null, LookupResolution.None.INSTANCE, RoutineResolution.None.INSTANCE);
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

    @Test
    void orderByArg_helperMethod_takesEnvAndAliasedTableParameters() {
        // The Table is a parameter (not a local declaration) so the same helper serves root
        // callers (pass the canonical tableLocal) and Split+Connection callers (pass the
        // FK-chain terminal alias).
        var field = queryTableFieldWithOrderByArg("films");
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(spec, "filmsOrderBy").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingEnvironment",
                "no.sikt.graphitron.rewrite.test.jooq.tables.Film");
    }

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
            List.of(new OrderBySpec.ColumnOrderEntry(TestFixtures.filmIdCol(), null, OrderBySpec.SortDirection.ASC)), true);
        return new QueryField.QueryTableField("Query", name, null,
            TestFixtures.tableBoundFilm(new FieldWrapper.Connection(true, 100)),
            List.of(), orderBy, forwardPagination(), LookupResolution.None.INSTANCE, RoutineResolution.None.INSTANCE);
    }

    private static QueryField.QueryTableField connectionFieldWithArgOrderBy(String name) {
        var filmIdCol = TestFixtures.filmIdCol();
        var base = new OrderBySpec.Fixed(
            List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null, OrderBySpec.SortDirection.ASC)), true);
        var namedOrder = new OrderBySpec.NamedOrder(
            "TITLE",
            new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(filmIdCol, null, OrderBySpec.SortDirection.ASC)), true));
        var orderBy = new OrderBySpec.Argument(
            "order", "FilmOrder", false, false, "field", "direction",
            List.of(namedOrder), base);
        return new QueryField.QueryTableField("Query", name, null,
            TestFixtures.tableBoundFilm(new FieldWrapper.Connection(true, 100)),
            List.of(), orderBy, forwardPagination(), LookupResolution.None.INSTANCE, RoutineResolution.None.INSTANCE);
    }

    @Test
    void connectionField_returnsConnectionResult() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(connectionField("films")));
        // Wrapped in DataFetcherResult<...>.
        assertThat(method(spec, "films").returnType().toString()).endsWith("ConnectionResult>");
    }

    @Test
    void connectionField_withOrderByArg_emitsHelperMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null,
            List.of(connectionFieldWithArgOrderBy("films")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("filmsOrderBy");
    }

    // Custom pagination arg names are unsupported: PaginationResolver.isPaginationArg accepts
    // only first/last/after/before, so the slot fixes the name and PaginationArg carries none.

    // ===== Backward pagination and Relay validation =====
    //
    // No body assertions here. Backward pagination (last/before), the first+last conflict
    // rejection, and cursor decode + seek semantics are covered end-to-end by the
    // filmsConnection_* execution tests in graphitron-sakila-example's GraphQLQueryTest.
    // reverseOrderBy derivation lives in ConnectionHelper.pageRequest, not on the fetcher class.

    @Test
    void connectionField_withOrderByArg_extraFieldsComeFromOrderingResult() {
        // Intentional body-content assertion; no structural equivalent. Both orderBy (for SQL)
        // and extraFields (for cursor) must derive from the same OrderByResult dispatch, the
        // same "ordering" local. If they diverge, SQL ORDER BY and cursor columns get out of
        // sync, which execution tier only catches on a specific multi-column ordering query
        // with cursor pagination. The composition lives in the rows<Field> launcher unit; the
        // entry point is thin.
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null,
            List.of(connectionFieldWithArgOrderBy("films")));
        var code = method(spec, "rowsFilms").code().toString();
        assertThat(code).contains("ordering.sortFields()");
        assertThat(code).contains("ordering.columns()");
    }

    // ===== Service root fetchers =====

    @Test
    void queryServiceTableField_emittedFetcher_declaresTypedResult() {
        // List-cardinality @table-bound @service field returns Result<FilmRecord> typed, not
        // Object. Body-shape properties (the optional dsl local, direct service call, no
        // projection) are asserted at execution tier: GraphQLQueryTest.queryServiceTable_filmsByService_*.
        var method = TestFixtures.staticServiceMethodRef(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "filmsByService",
            ParameterizedTypeName.get(
                ClassName.get("org.jooq", "Result"),
                ClassName.get("no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord")),
            List.of(
                new MethodRef.Param.Typed("dsl", "org.jooq.DSLContext", new ParamSource.DslContext()),
                new MethodRef.Param.Typed("ids", "java.util.List<java.lang.Integer>",
                    new ParamSource.Arg(new CallSiteExtraction.Direct(), no.sikt.graphitron.rewrite.PathExpr.head("ids")))));
        var field = new QueryField.QueryServiceTableField("Query", "filmsByService", null,
            TestFixtures.tableBoundFilm(nonNullList()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "filmsByService").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_declaresScalarReturnFromMethodReflection() {
        // ScalarReturnType faithfully reflects the developer's declared return type, no widening
        // to Object. The behavioural round-trip (graphql-java coercing Integer to GraphQL Int!)
        // is asserted at execution tier: GraphQLQueryTest.queryServiceRecord_filmCount_*.
        var method = TestFixtures.staticServiceMethodRef(
            "no.sikt.graphitron.rewrite.test.services.SampleQueryService",
            "filmCount",
            ClassName.get("java.lang", "Integer"),
            List.of(new MethodRef.Param.Typed("dsl", "org.jooq.DSLContext", new ParamSource.DslContext())));
        var field = new QueryField.QueryServiceRecordField("Query", "filmCount", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        // Every fetcher's return is wrapped in DataFetcherResult<P>; ScalarReturnType still
        // surfaces the developer's reflected return type as the inner P.
        assertThat(method(spec, "filmCount").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.Integer>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesPrimitiveReturnType() {
        // Reflection of `int filmCount()` produces returnTypeName "int". The emitter must
        // declare the primitive faithfully on the inner P slot; boxing to Integer only
        // happens because DataFetcherResult<P> requires a reference type for P.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "filmCount", TypeName.INT, List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "filmCount", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "filmCount").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.Integer>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesArrayReturnType() {
        // Reflection of `String[] tags()` produces returnTypeName "java.lang.String[]". The
        // emitter must preserve the array shape faithfully via ArrayTypeName.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "tags",
            ArrayTypeName.of(ClassName.get("java.lang", "String")), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "tags", null,
            new ReturnTypeRef.ScalarReturnType("Tags", single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "tags").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.String[]>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesMultiArgGenericReturnType() {
        // Reflection of `Map<String, Integer> stats()` produces a multi-arg type name. The
        // emitter's depth-aware comma splitter must yield ParameterizedTypeName with two args.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "stats",
            ParameterizedTypeName.get(
                ClassName.get("java.util", "Map"),
                ClassName.get("java.lang", "String"),
                ClassName.get("java.lang", "Integer")), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "stats", null,
            new ReturnTypeRef.ScalarReturnType("Stats", single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "stats").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.Map<java.lang.String, java.lang.Integer>>");
    }

    @Test
    void queryServiceRecordField_emittedFetcher_handlesBoundedWildcardReturnType() {
        // Reflection of `List<? extends Number> nums()` produces "java.util.List<? extends
        // java.lang.Number>". The emitter must preserve the wildcard via WildcardTypeName.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "nums",
            ParameterizedTypeName.get(
                ClassName.get("java.util", "List"),
                WildcardTypeName.subtypeOf(ClassName.get("java.lang", "Number"))), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "nums", null,
            new ReturnTypeRef.ScalarReturnType("Nums", single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "nums").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<? extends java.lang.Number>>");
    }

    // ===== try/catch wrapper: Outcome-wrapper arm vs redact arm =====

    private static ErrorChannel.Mapped sakMappedChannel() {
        // A root @service outcome field's channel: Mapped (the Outcome-wrapper transport).
        // One handler suffices; the catch-arm emission walks only mappingsConstantName.
        var errorType = new no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType(
            "SakError", null,
            List.of(new no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler(
                "java.lang.RuntimeException", Optional.empty(), Optional.empty())),
            List.of());
        return new ErrorChannel.Mapped(List.of(errorType), "SAK_PAYLOAD");
    }

    @Test
    void queryServiceRecordField_withMappedChannel_wrapsInOutcomeAndWalksMappings() {
        // A present channel on a root @service field is always Mapped: the fetcher's payload
        // type wraps to Outcome<X>, the success arm returns Success, and the catch arm walks
        // the channel's mapping table into Outcome.ErrorList (no ErrorRouter.dispatch; the
        // router seams serve the RouterDispatched partition on other field variants).
        // No-channel fields still route through redact, covered by every service-record test
        // that passes Optional.empty().
        var method = TestFixtures.staticServiceMethodRef(
            "no.sikt.graphitron.rewrite.TestServiceStub", "runSak",
            ClassName.bestGuess("com.example.SakPayload"), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "sak", null,
            new ReturnTypeRef.ScalarReturnType("SakPayload", single()),             TestFixtures.stubServiceCall(method),
            Optional.of(sakMappedChannel()));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var emitted = method(spec, "sak");
        assertThat(emitted.returnType().toString()).contains(".schema.Outcome<");
        var body = emitted.code().toString();
        assertThat(body).contains("ErrorMappings.SAK_PAYLOAD");
        assertThat(body).contains("mapping.match(cause)");
        assertThat(body).contains("ErrorList<>(");
        assertThat(body).doesNotContain("ErrorRouter.dispatch");
    }

    @Test
    void queryServiceRecordField_withoutErrorChannel_catchArmSurfacesOrRedacts() {
        // Counter-test: an absent channel keeps the no-channel disposition, which routes
        // through surfaceClientErrorOrRedact. Same fetcher shape as the dispatch test above but with
        // Optional.empty() for the channel.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "filmCount", ClassName.get("java.lang", "Integer"), List.of());
        var field = new QueryField.QueryServiceRecordField("Query", "filmCount", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var body = method(spec, "filmCount").code().toString();
        assertThat(body).contains("ErrorRouter.surfaceClientErrorOrRedact(e, env)");
        assertThat(body).doesNotContain("ErrorRouter.dispatch");
        assertThat(body).doesNotContain("ErrorMappings");
    }

    // The @table-bound service path shares buildServiceFetcherCommon, but its channel is
    // always empty (the classifier's outcome-channel resolution requires a result-mapped
    // return), so its catch arm is the no-channel disposition covered by
    // mutationServiceTableField_withoutErrorChannel_catchArmSurfacesOrRedacts.

    // ===== MutationServiceTableField / MutationServiceRecordField =====
    //
    // Mutation services share buildServiceFetcherCommon with the query side, so the
    // try/catch wrapper and Jakarta validation pre-step carry over for free; the success
    // arm is universal passthrough. Tests below assert that the mutation switch arms reach
    // the helper (rather than emitting a stub) and that the wrapper integration is observable
    // on the emitted body.

    @Test
    void mutationServiceTableField_emittedFetcher_callsServiceMethod() {
        // Pipeline-tier: the variant un-stubs and the body is the shared service-fetcher shape.
        // Asserting on the body's reference to the service class catches a regression to stub(f)
        // (which would emit `throw new UnsupportedOperationException(...)`), and asserts the
        // service class is the call target.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "createFilm",
            ClassName.get("no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord"),
            List.of());
        var field = new MutationField.MutationServiceTableField("Mutation", "createFilm", null,
            tableBoundFilm(single()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var emitted = method(spec, "createFilm");
        assertThat(emitted.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>");
        var body = emitted.code().toString();
        assertThat(body).contains("com.example.Service.createFilm");
        // Stub variants throw from a fresh body; the real emitter goes through the try block.
        assertThat(body).doesNotContain("UnsupportedOperationException");
        assertThat(body).contains("try");
    }

    @Test
    void mutationServiceTableField_listReturn_declaresResultOfRecord() {
        // Mirrors queryServiceTableField_emittedFetcher_declaresTypedResult; the table-bound
        // mutation service shape is identical.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "createFilms",
            ParameterizedTypeName.get(
                ClassName.get("org.jooq", "Result"),
                ClassName.get("no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord")),
            List.of());
        var field = new MutationField.MutationServiceTableField("Mutation", "createFilms", null,
            tableBoundFilm(nonNullList()), TestFixtures.stubServiceCall(method), Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "createFilms").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Result<no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord>>");
    }

    @Test
    void mutationServiceRecordField_emittedFetcher_callsServiceMethod() {
        // Pipeline-tier: the non-table variant un-stubs and the body is the shared shape.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "doThing", ClassName.get("java.lang", "Integer"), List.of());
        var field = new MutationField.MutationServiceRecordField("Mutation", "doThing", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()),             TestFixtures.stubServiceCall(method),
            Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var emitted = method(spec, "doThing");
        assertThat(emitted.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.lang.Integer>");
        var body = emitted.code().toString();
        assertThat(body).contains("com.example.Service.doThing");
        assertThat(body).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void mutationServiceRecordField_resultReturnType_withFqClassName_declaresTypedReturn() {
        // ResultReturnType with a non-null fqClassName produces a typed declaration on the
        // fetcher's inner P slot, same policy as
        // queryServiceRecordField_emittedFetcher_declaresScalarReturnFromMethodReflection.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "createFilm",
            ClassName.bestGuess("com.example.Film"), List.of());
        var field = new MutationField.MutationServiceRecordField("Mutation", "createFilm", null,
            new ReturnTypeRef.ResultReturnType("Film", single(), "com.example.Film"),             TestFixtures.stubServiceCall(method),
            Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        assertThat(method(spec, "createFilm").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<com.example.Film>");
    }

    @Test
    void mutationServiceRecordField_withMappedChannel_wrapsInOutcomeAndWalksMappings() {
        // Wrapper integration on the mutation side: a present (Mapped) channel wraps the
        // payload in Outcome and the catch arm walks the channel's mapping table into
        // Outcome.ErrorList, mirroring the query-side test above.
        var method = TestFixtures.staticServiceMethodRef(
            "no.sikt.graphitron.rewrite.TestServiceStub", "createSak",
            ClassName.bestGuess("com.example.SakPayload"), List.of());
        var field = new MutationField.MutationServiceRecordField("Mutation", "createSak", null,
            new ReturnTypeRef.ScalarReturnType("SakPayload", single()),             TestFixtures.stubServiceCall(method),
            Optional.of(sakMappedChannel()));
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var emitted = method(spec, "createSak");
        assertThat(emitted.returnType().toString()).contains(".schema.Outcome<");
        var body = emitted.code().toString();
        assertThat(body).contains("ErrorMappings.SAK_PAYLOAD");
        assertThat(body).contains("mapping.match(cause)");
        assertThat(body).contains("ErrorList<>(");
        assertThat(body).doesNotContain("ErrorRouter.dispatch");
    }

    @Test
    void mutationServiceTableField_withoutErrorChannel_catchArmSurfacesOrRedacts() {
        // Counter-test: an absent channel keeps the no-channel disposition
        // (surfaceClientErrorOrRedact) on the mutation side too. Without this assertion, a regression that hard-wired
        // dispatch in the mutation emitter (rather than going through the shared common helper's
        // fork) would slip through.
        var method = TestFixtures.staticServiceMethodRef(
            "com.example.Service", "doThing", ClassName.get("java.lang", "Integer"), List.of());
        var field = new MutationField.MutationServiceRecordField("Mutation", "doThing", null,
            new ReturnTypeRef.ScalarReturnType("Int", single()),             TestFixtures.stubServiceCall(method),
            Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var body = method(spec, "doThing").code().toString();
        assertThat(body).contains("ErrorRouter.surfaceClientErrorOrRedact(e, env)");
        assertThat(body).doesNotContain("ErrorRouter.dispatch");
    }

    // ===== typed DialectRequirement rendered as the request-time guard =====
    //
    // emitDialectGuard renders the guard from the model's typed DialectRequirement. The
    // RequiresFamily(POSTGRES) branch is exercised end-to-end by the reachable bulk-UPDATE
    // pipeline path (FetcherPipelineTest). The Upsert branch's RejectsFamily(ORACLE) cannot
    // classify through the pipeline (UPSERT is refused at the classifier dispatch and deferred),
    // so its derivation and guard rendering are pinned here against a directly-constructed
    // field. The None branch (INSERT / DELETE / single UPDATE) must emit no guard at all.

    @Test
    void upsertFetcher_rejectsFamilyOracle_emitsDialectGuardFromModel() {
        // The Oracle rejection derives on the model from the Upsert write arm; emitDialectGuard
        // renders it as a self-contained family() == "ORACLE" check throwing the derived
        // reason() message. The
        // emitted code references no generator-internal class (graphitron is test-scoped in
        // consumers; these fetchers compile as consumer main sources).
        var upsert = new MutationField.DmlTableField(
            "Mutation", "upsertFilm", null,
            new DmlReturnExpression.EncodedSingle(new HelperRef.Encode(
                ClassName.get("fake.code.generated", "NodeIdEncoder"), "encodeFilm",
                List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Long")))),
            new no.sikt.graphitron.rewrite.model.OperationMember.Write.Upsert(
                ArgumentRef.InputTypeArg.TableInputArg.of(
                    "in", "FilmInput", true, false, FILM_TABLE, List.of(), Optional.empty(), List.of())),
            Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(upsert), DEFAULT_OUTPUT_PACKAGE);

        var body = method(spec, "upsertFilm").code().toString();
        assertThat(body)
            .as("guard is self-contained (no generator-internal SqlDialectFamily reference in "
                + "emitted code) and rejects the ORACLE family via jOOQ's own family() collapse")
            .contains("if (\"ORACLE\".equals(dsl.dialect().family().name()))")
            .doesNotContain("SqlDialectFamily")
            .as("throws UnsupportedOperationException carrying the model's reason() message")
            .contains("throw new java.lang.UnsupportedOperationException(")
            .contains("@mutation(typeName: UPSERT) is not supported on Oracle");
    }

    @Test
    void insertFetcher_noneRequirement_emitsNoDialectGuard() {
        // The Insert arm derives DialectRequirement.None; the None arm of emitDialectGuard emits nothing,
        // so the fetcher body references no SqlDialectFamily and throws no dialect guard.
        var insert = new MutationField.DmlTableField(
            "Mutation", "createFilm", null,
            new DmlReturnExpression.EncodedSingle(new HelperRef.Encode(
                ClassName.get("fake.code.generated", "NodeIdEncoder"), "encodeFilm",
                List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Long")))),
            new no.sikt.graphitron.rewrite.model.OperationMember.Write.Insert(
                ArgumentRef.InputTypeArg.TableInputArg.of(
                    "in", "FilmInput", true, false, FILM_TABLE, List.of(), Optional.empty(), List.of())),
            Optional.empty());
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(insert), DEFAULT_OUTPUT_PACKAGE);

        var body = method(spec, "createFilm").code().toString();
        assertThat(body)
            .doesNotContain("SqlDialectFamily")
            .doesNotContain("UnsupportedOperationException");
    }

    @Test
    void mutationServiceRecordField_withValidationHandler_emitsValidatorPreStep() {
        // Validation wrapper integration on the mutation side: when the channel carries any
        // ValidationHandler, the shared helper inserts the pre-execution Jakarta validation
        // block. The block is emitted ahead of the try, walks every Arg-sourced parameter, and
        // short-circuits with the payload's errors-arm filled by the violations.
        var method = TestFixtures.staticServiceMethodRef(
            "no.sikt.graphitron.rewrite.TestServiceStub", "createSak",
            ClassName.bestGuess("com.example.SakPayload"),
            List.of(new MethodRef.Param.Typed("input", "com.example.SakInput",
                new ParamSource.Arg(new CallSiteExtraction.Direct(), no.sikt.graphitron.rewrite.PathExpr.head("input")))));
        var validationErr = new no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType(
            "SakValidationErr",
            null,
            List.of(new no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler(Optional.empty())),
            List.of());
        // @service outcome fields carry ErrorChannel.Mapped; the validator pre-step is gated
        // on Mapped and emits the Outcome.ErrorList early return.
        var channel = new ErrorChannel.Mapped(List.of(validationErr), "SAK_PAYLOAD");
        var field = new MutationField.MutationServiceRecordField("Mutation", "createSak", null,
            new ReturnTypeRef.ScalarReturnType("SakPayload", single()),             TestFixtures.stubServiceCall(method),
            Optional.of(channel));
        var spec = TypeFetcherGenerator.generateTypeSpec("Mutation", null, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);

        var body = method(spec, "createSak").code().toString();
        assertThat(body).contains("graphitronContext(env).getValidator(env)");
        assertThat(body).contains("ConstraintViolations.toGraphQLError");
        assertThat(body.indexOf("getValidator(env)")).isLessThan(body.indexOf("try"));
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
        var code = method(spec, "rowsAllContent").code().toString();
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
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code).doesNotContain(".in(");
    }

    @Test
    void queryTableInterfaceField_noAsterisk_inSelectClause() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "rowsAllContent").code().toString()).doesNotContain("asterisk()");
    }

    @Test
    void queryTableInterfaceField_discriminatorAlwaysSelected() {
        var field = queryTableInterfaceField("allContent", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "rowsAllContent").code().toString()).contains("\"FILM_TYPE\"");
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
            DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code).contains("FilmContent.$project(");
        assertThat(code).contains("ShortContent.$project(");
    }

    // ===== TableInterfaceField =====

    private static ChildField.TableInterfaceField tableInterfaceField(String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = tableBoundFilm(wrapper);
        // Fixture: parent (Language) holds the FK → child (Film) PK.
        // FK hop: source=Film(language_id), target=Language(language_id).
        List<JoinStep> joinPath = List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), LANGUAGE_TABLE,
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
        List<JoinStep> joinPath = List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), LANGUAGE_TABLE,
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
        List<JoinStep> joinPath = List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), LANGUAGE_TABLE,
            List.of(languageIdCol()), FILM_TABLE, List.of(languageIdCol()), null, "content_0"));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM"),
            new ParticipantRef.TableBound("ShortContent", filmTable(), "SHORT"));
        var field = new ChildField.TableInterfaceField("Language", "content", null, returnType,
            "content_type", List.of("FILM", "SHORT"), participants,
            joinPath, List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "content").code().toString();
        assertThat(code).contains("FilmContent.$project(");
        assertThat(code).contains("ShortContent.$project(");
    }

    // ===== Cross-table participant fields =====
    //
    // The interface fetcher emits a conditional LEFT JOIN per cross-table participant field. The
    // gating uses the graphql-java type-scoped selection-set API (Type.field); the JOIN's ON
    // clause includes the participant's discriminator equality so non-matching rows project NULL
    // for the cross-table column rather than spuriously matching every row.

    private static ParticipantRef.TableBound.CrossTableField filmContentRatingCrossTable() {
        var ratingCol = new ColumnRef("rating", "RATING", "java.lang.String");
        var contentToFilmFk = TestFixtures.fkJoin(TestFixtures.foreignKeyRef("content_film_id_fkey"), joinTarget("content"),
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
            DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
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
            DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
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
            DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
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
            DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code)
            .as("no LEFT JOIN when no participant declares cross-table fields")
            .doesNotContain(".leftJoin(");
    }

    @Test
    void tableInterfaceField_crossTableField_emitsLeftJoinAtChildSite() {
        // Both interface consumers (the Query launcher and the ChildField-rooted legacy
        // fetcher) share the relocated DiscriminatedTableFragments assembly; this asserts the
        // emission applies at the child site too.
        var returnType = tableBoundFilm(nonNullList());
        List<JoinStep> joinPath = List.of(TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_language_id_fkey"), LANGUAGE_TABLE,
            List.of(languageIdCol()), FILM_TABLE, List.of(languageIdCol()), null, "content_0"));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM",
                List.of(filmContentRatingCrossTable())));
        var field = new ChildField.TableInterfaceField("Language", "content", null, returnType,
            "content_type", List.of("FILM"), participants,
            joinPath, List.of(), new OrderBySpec.None(), null);
        var spec = TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, null,
            List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "content").code().toString();
        assertThat(code).contains("env.getSelectionSet().contains(\"FilmContent.rating\")");
        assertThat(code).contains("step = step.leftJoin(FilmContent_rating_alias).on(");
    }

    // ===== discriminator qualifies off the FROM table instance, not the @table directive =====
    //
    // The discriminator column must qualify to the table jOOQ renders in the FROM clause, produced
    // by the table instance's own getQualifiedName(). Qualifying off the verbatim @table(name:)
    // directive string diverges from the rendered FROM token whenever the
    // directive name differs in case or schema, so Postgres rejects the query with
    // "missing FROM-clause entry". These fixtures give the base a deliberately mismatched directive
    // name distinct from both the jOOQ-derived local variable (filmTable) and the column (FILM_TYPE),
    // so a regression back to directive-string qualification surfaces as that literal, which the
    // final assertion forbids.

    private static final String DISCRIMINATOR_DIRECTIVE_NAME = "INTERFACE_BASE";

    /**
     * Single-table discriminated interface whose base {@code @table(name:)} echo
     * ({@code INTERFACE_BASE}) is case- and name-mismatched against the jOOQ-derived FROM token,
     * exercising all three discriminator emit sites (routing projection, IN filter, LEFT JOIN gate)
     * in one Query fetcher body.
     */
    private static QueryField.QueryTableInterfaceField discriminatedAllContent() {
        var base = TestFixtures.tableRef(DISCRIMINATOR_DIRECTIVE_NAME, "FILM", "Film", List.of());
        var returnType = TestFixtures.tableBound("Film", base, nonNullList());
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("FilmContent", filmTable(), "FILM",
                List.of(filmContentRatingCrossTable())),
            new ParticipantRef.TableBound("ShortContent", filmTable(), "SHORT"));
        return new QueryField.QueryTableInterfaceField("Query", "allContent", null, returnType,
            "FILM_TYPE", List.of("FILM", "SHORT"), participants,
            List.of(), new OrderBySpec.None(), null);
    }

    @Test
    void queryTableInterfaceField_discriminatorProjection_qualifiesOffTableInstance() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(discriminatedAllContent()), DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code)
            .as("the __discriminator__ routing projection qualifies off the FROM table instance")
            .contains("filmTable.getQualifiedName().append(org.jooq.impl.DSL.name(\"FILM_TYPE\")), java.lang.Object.class).as(\"__discriminator__\")");
    }

    @Test
    void queryTableInterfaceField_discriminatorFilter_qualifiesOffTableInstance() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(discriminatedAllContent()), DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code)
            .as("the IN-filter discriminator restriction qualifies off the FROM table instance")
            .contains("filmTable.getQualifiedName().append(org.jooq.impl.DSL.name(\"FILM_TYPE\")), java.lang.Object.class).in(");
    }

    @Test
    void queryTableInterfaceField_discriminatorJoinGate_qualifiesOffTableInstance() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(discriminatedAllContent()), DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code)
            .as("the LEFT JOIN ON-clause discriminator gate qualifies off the FROM table instance")
            .contains("filmTable.getQualifiedName().append(org.jooq.impl.DSL.name(\"FILM_TYPE\")), java.lang.Object.class).eq(\"FILM\")");
    }

    @Test
    void queryTableInterfaceField_discriminator_neverQualifiesOffDirectiveName() {
        // Regression lock: the verbatim @table(name:) directive string must not appear as a
        // SQL-name qualifier at any discriminator site. Directive-string qualification renders
        // DSL.name("INTERFACE_BASE", "FILM_TYPE"), which diverges from the FROM token, and
        // Postgres rejects the query with "missing FROM-clause entry".
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(discriminatedAllContent()), DEFAULT_OUTPUT_PACKAGE);
        var code = method(spec, "rowsAllContent").code().toString();
        assertThat(code)
            .as("the @table directive name must not qualify any discriminator reference")
            .doesNotContain(DISCRIMINATOR_DIRECTIVE_NAME);
    }

    @Test
    void graphitronContextHelper_targetsLocallyEmittedInterfaceByClassKey() {
        // Pins GraphitronContext's home (the generated <outputPackage>.schema package) and the
        // typed GraphitronContext.class context key (not a string key).
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null,
            List.of(queryTableField("film", false)), DEFAULT_OUTPUT_PACKAGE);
        var helper = method(spec, "graphitronContext");
        var expectedFqn = DEFAULT_OUTPUT_PACKAGE + ".schema.GraphitronContext";
        assertThat(helper.returnType().toString())
            .as("retargeted to the locally-emitted interface under the output package")
            .isEqualTo(expectedFqn);
        assertThat(helper.code().toString())
            .as("keys on the typed class, not a string")
            .contains("env.getGraphQlContext().get(" + expectedFqn + ".class)");
    }

    @Test
    void graphitronContextHelper_emittedForServiceRecordOnlyClass() {
        // ServiceRecordField is the only BatchKeyField that doesn't extend SqlGeneratingField
        // via TableTargetField, so a predicate enumerating SqlGeneratingField subtypes silently
        // drops it: the fetcher emits a graphitronContext(env) call (via buildDataLoaderName)
        // with no graphitronContext helper method. Helper emission is gated on a request
        // recorded through TypeFetcherEmissionContext.graphitronContextCall().
        var field = scalarServiceRecordField(
            "Language", "displayName", false,
            rowShape(),
            ClassName.get(String.class));
        var spec = specWith(field);
        assertThat(spec.methodSpecs())
            .as("helper is emitted whenever any emitter records a graphitronContext request")
            .extracting(MethodSpec::name)
            .contains("graphitronContext");
    }

    @Test
    void graphitronContextHelper_notEmittedWhenNoBodyReferencesIt() {
        // Negative direction of the invariant: if no emitter records a graphitronContext
        // request, the helper is not emitted. ColumnField on a table-backed parent emits no
        // method (wired via FetcherRegistrationsEmitter / ColumnFetcher), so nothing requests
        // the helper.
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs())
            .as("no emitted methods → no graphitronContext helper")
            .extracting(MethodSpec::name)
            .doesNotContain("graphitronContext");
    }

    // ===== ServiceRecordField (scalar / record-backed return) =====
    //
    // ServiceRecordField shares the DataLoader emitters with ServiceTableField; the only axis
    // of variation is the per-key value type (perKeyType): RECORD for table-bound,
    // field.elementType() for record-bound. These tests assert that the parameterisation
    // surfaces the correct loader signature, factory selection, and rows-method shape for
    // each variant.

    private static no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField scalarServiceRecordField(
            String parentType, String name, boolean isList, ServiceSourceShape shape, no.sikt.graphitron.javapoet.TypeName perKeyType) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = new no.sikt.graphitron.rewrite.model.ReturnTypeRef.ScalarReturnType("String", returnWrapper);
        return serviceRecordField(parentType, name, "getValues", returnType, shape, perKeyType);
    }

    private static no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField recordBackedServiceRecordField(
            String parentType, String name, boolean isList, ServiceSourceShape shape, String fqBackingClass) {
        var returnWrapper = isList ? (FieldWrapper) listWrapper() : single();
        var returnType = new no.sikt.graphitron.rewrite.model.ReturnTypeRef.ResultReturnType(
            "FilmDetails", returnWrapper, fqBackingClass);
        return serviceRecordField(parentType, name, "getDetails", returnType, shape,
            ClassName.bestGuess(fqBackingClass));
    }

    private static no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField serviceRecordField(
            String parentType, String name, String methodName,
            no.sikt.graphitron.rewrite.model.ReturnTypeRef returnType, ServiceSourceShape shape,
            no.sikt.graphitron.javapoet.TypeName perKeyType) {
        var container = shape.mapped()
            ? no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.MAPPED_SET
            : no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.POSITIONAL_LIST;
        var sourceKey = TestFixtures.serviceSourceKey(shape.wrap(), shape.columns());
        // The method ref declares the outer loader-container wrap over the per-key V, the
        // shape the classifier acceptance guarantees for every corpus member (the validator's
        // strict return-type equality); the rows method returns it verbatim.
        var outerReturn = no.sikt.graphitron.rewrite.model.RowsMethodShape.outerRowsReturnType(
            perKeyType, returnType, sourceKey.keyElementType(), shape.mapped());
        var method = TestFixtures.staticServiceMethodRef(
            "no.example.Service", methodName, outerReturn,
            List.of(TestFixtures.sourced("keys", shape.wrap(), shape.columns(), container)));
        return new no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField(
            parentType, name, null, returnType, List.of(), method,
            sourceKey,
            TestFixtures.loaderRegistration(returnType, shape.mapped(), false),
            java.util.Optional.empty());
    }

    private static ServiceSourceShape rowShape() {
        return new ServiceSourceShape(new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Row(),
            List.of(languageIdCol()), false);
    }

    private static TypeSpec specWith(GraphitronField field) {
        return TypeFetcherGenerator.generateTypeSpec("Language", LANGUAGE_TABLE, List.of(field));
    }

    @Test
    void serviceRecordField_scalar_single_loaderValueIsPerKeyType() {
        var field = scalarServiceRecordField(
            "Language", "displayName", false,
            rowShape(),
            ClassName.get(String.class));
        assertThat(method(specWith(field), "displayName").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.lang.String>>");
    }

    @Test
    void serviceRecordField_scalar_list_loaderValueIsListOfPerKeyType() {
        var field = scalarServiceRecordField(
            "Language", "displayNames", true,
            rowShape(),
            ClassName.get(String.class));
        assertThat(method(specWith(field), "displayNames").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<java.lang.String>>>");
    }

    @Test
    void serviceRecordField_recordBacked_single_loaderValueIsBackingClass() {
        var field = recordBackedServiceRecordField(
            "Language", "details", false,
            rowShape(),
            "no.example.FilmDetails");
        assertThat(method(specWith(field), "details").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<no.example.FilmDetails>>");
    }

    @Test
    void serviceRecordField_mappedRow_list_dataFetcherCallsNewMappedDataLoaderWithSetKeys() {
        var field = scalarServiceRecordField(
            "Language", "displayNames", true,
            new ServiceSourceShape(new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Row(), List.of(languageIdCol()), true),
            ClassName.get(String.class));
        var body = method(specWith(field), "displayNames").code().toString();
        assertThat(body).contains("newMappedDataLoader(");
        assertThat(body).doesNotContain("newDataLoaderWithContext");
    }

    @Test
    void serviceRecordField_mappedRow_list_rowsMethodReturnsMapToListOfElement() {
        var field = scalarServiceRecordField(
            "Language", "displayNames", true,
            new ServiceSourceShape(new no.sikt.graphitron.rewrite.model.SourceKey.Wrap.Row(), List.of(languageIdCol()), true),
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
            rowShape(),
            ClassName.get(String.class));
        // Positional + single-cardinality field: rows-method returns List<V>, ordered by input
        // key index (ServiceTableField shape parity).
        var rows = method(specWith(field), "loadDisplayName");
        assertThat(rows.returnType().toString())
            .isEqualTo("java.util.List<java.lang.String>");
    }

    // ===== QueryInterfaceField / QueryUnionField (multi-table polymorphic) =====
    //
    // Two-stage emission: Stage 1 narrow UNION ALL projecting (typename, pk, sort) per branch.
    // Stage 2 per-typename batched lookup using the ValuesJoinRowBuilder primitive
    // with the dispatcher-shape .on(...) join. Result records carry __typename so the
    // schema-class TypeResolver routes each row to its concrete GraphQL type.

    private static QueryField.QueryInterfaceField queryInterfaceField(String name, boolean isList,
                                                                       List<ParticipantRef> participants) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Searchable", wrapper);
        return new QueryField.QueryInterfaceField("Query", name, null, returnType, participants, List.of());
    }

    private static QueryField.QueryUnionField queryUnionField(String name, boolean isList,
                                                               List<ParticipantRef> participants) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Document", wrapper);
        return new QueryField.QueryUnionField("Query", name, null, returnType, participants, List.of());
    }

    private static List<ParticipantRef> filmAndActorParticipants() {
        return List.of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                TestFixtures.tableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
    }

    @Test
    void queryInterfaceField_list_returnsListOfRecord() {
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(spec, "search").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>");
    }

    @Test
    void queryInterfaceField_single_returnsRecord() {
        var field = queryInterfaceField("documentById", false, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(spec, "documentById").returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<org.jooq.Record>");
    }

    @Test
    void queryInterfaceField_emitsTwoStageStructure() {
        // Stage 1: narrow UNION ALL projecting (__typename, __pk0__, __sort__) per branch.
        // Stage 2: per-typename dispatch into select<Participant>For<Field> helpers.
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
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
            DEFAULT_OUTPUT_PACKAGE);
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
        // Stage 2 invokes <Type>.$project(PolymorphicSelectionSet.restrictTo(env.getSelectionSet(),
        // "<Type>").getFieldsGroupedByResultKey(), t, env); PolymorphicSelectionSet.restrictTo wraps the
        // parent selection set so each per-typename SELECT projects only columns actually selected for that variant.
        // Selection-set narrowing works at full strength only with $project, not asterisk().
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var film = method(spec, "selectFilmForSearch");
        var actor = method(spec, "selectActorForSearch");
        assertThat(film.code().toString())
            .contains(".Film.$project(")
            .contains(".PolymorphicSelectionSet.restrictTo(env.getSelectionSet(), \"Film\").getFieldsGroupedByResultKey(), t, env)");
        assertThat(actor.code().toString())
            .contains(".Actor.$project(")
            .contains(".PolymorphicSelectionSet.restrictTo(env.getSelectionSet(), \"Actor\").getFieldsGroupedByResultKey(), t, env)");
    }

    @Test
    void queryInterfaceField_perTypenameHelpers_useDispatcherShapeOnNotUsing() {
        // Dispatcher uses .on(...) not .using(...) because the SELECT
        // projection includes <Type>.$project(...) which references t.<col> directly.
        // USING would collapse joined columns and risk colliding with $project-emitted projections.
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "selectFilmForSearch").code().toString();
        assertThat(body)
            .as("dispatcher shape uses .on(...) for the values-derived join")
            .contains(".join(input).on(");
        assertThat(body)
            .as("does not use .using(...) — would collapse t.<col> with $project projections")
            .doesNotContain(".join(input).using(");
    }

    @Test
    void queryInterfaceField_perTypenameHelpers_addTypenameLiteralToSelect() {
        // Each typed Record carries the synthetic __typename column so the schema-class
        // TypeResolver (registered by GraphitronSchemaClassGenerator) routes
        // each row back to its concrete GraphQL type.
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "selectFilmForSearch").code().toString();
        assertThat(body).contains("inline(\"Film\")")
            .contains(".as(\"__typename\")");
    }

    @Test
    void queryInterfaceField_perTypenameHelpers_arePrivateStatic() {
        var field = queryInterfaceField("search", true, filmAndActorParticipants());
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(spec, "selectFilmForSearch").modifiers())
            .containsExactlyInAnyOrder(
                javax.lang.model.element.Modifier.PRIVATE,
                javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void queryInterfaceField_compositePkParticipant_emitsJsonbArraySortKey() {
        // Composite-key sort projects DSL.jsonbArray(pk1, pk2, ...).as("__sort__").
        // JSONB arrays compare element-wise in PostgreSQL, so composite ordering reduces to a
        // single comparable column at no extra Java cost.
        var compositeTable = TestFixtures.tableRef("bar", "BAR", "Bar",
            List.of(
                new ColumnRef("id_1", "ID_1", "java.lang.Integer"),
                new ColumnRef("id_2", "ID_2", "java.lang.Integer")));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Bar", compositeTable, null));
        var field = queryInterfaceField("compositeSearch", true, participants);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
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
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "search").code().toString();
        assertThat(body).contains(".unionAll(");
        assertThat(body).contains("\"__typename\"");
        assertThat(body).contains("selectFilmForSearch(")
            .contains("selectActorForSearch(");
    }

    @Test
    void queryInterfaceField_isImplementedLeaf_notInNotImplementedReasons() {
        // QueryInterfaceField and QueryUnionField are IMPLEMENTED_LEAVES; the partition test
        // guards the disjoint-partition invariant, this asserts membership directly.
        assertThat(TypeFetcherGenerator.IMPLEMENTED_LEAVES)
            .contains(QueryField.QueryInterfaceField.class, QueryField.QueryUnionField.class);
        assertThat(TypeFetcherGenerator.STUBBED_VARIANTS)
            .doesNotContainKeys(QueryField.QueryInterfaceField.class, QueryField.QueryUnionField.class);
    }

    // ===== Connection pagination on QueryInterfaceField / QueryUnionField =====
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
        return new QueryField.QueryInterfaceField("Query", name, null, returnType, participants, List.of());
    }

    private static QueryField.QueryUnionField queryUnionConnectionField(String name,
                                                                        List<ParticipantRef> participants,
                                                                        int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("Document", wrapper);
        return new QueryField.QueryUnionField("Query", name, null, returnType, participants, List.of());
    }

    @Test
    void queryInterfaceField_connection_returnsDataFetcherResultOfConnectionResult() {
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
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
            DEFAULT_OUTPUT_PACKAGE);
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
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body).contains(".asTable(\"pages\")");
        assertThat(body).contains(".unionAll(");
    }

    @Test
    void queryInterfaceField_connection_appliesSeekAndLimit() {
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
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
            DEFAULT_OUTPUT_PACKAGE);
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
        // ConnectionResult takes (List<Record>, PageRequest, Table<?>, Condition). The emitter
        // binds (payload, page, pagesTable, DSL.noCondition()) so ConnectionHelper.totalCount can run
        // SELECT count(*) FROM (UNION ALL) AS pages lazily on selection. Per-branch WHEREs (when
        // wired for child connections) live inside the union, so the outer condition is a no-op.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body).contains(".util.ConnectionResult(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())");
    }

    @Test
    void queryInterfaceField_connection_liftsUnionAllAsTableLocal() {
        // The UNION-ALL derived table is lifted to a local Table<?> pagesTable variable so the
        // same reference backs both the page query (.from(pagesTable)) and ConnectionResult.table()
        // for totalCount. Without the local, totalCount would have to re-emit the entire
        // UNION ALL, doubling the emission size.
        var field = queryInterfaceConnectionField("searchConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
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
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "searchConnection").code().toString();
        assertThat(body)
            .as("orderBy combines sort key + typename tiebreaker")
            .contains("List.of(sortField.asc(), tieField.asc())");
        assertThat(body)
            .as("extraFields drives both cursor encoding and seek; both columns appear")
            .contains("of(sortField, tieField)");
    }

    @Test
    void queryInterfaceField_connection_compositePkParticipant_typesSortFieldAsJsonb() {
        // When any connection-mode participant has composite PK, the emitter types
        // sortField as Field<JSONB> instead of Field<ParticipantPkClass>. The synthetic __sort__
        // column is then projected as DSL.jsonbArray(...) per branch (see branchProjection +
        // buildPerTypenameSelect) and PostgreSQL's lexicographic JSONB ordering reproduces the
        // multi-column ordering across the union.
        var compositeTable = TestFixtures.tableRef("paged_a", "PAGED_A", "PagedA",
            List.of(
                new ColumnRef("k1", "K1", "java.lang.Integer"),
                new ColumnRef("k2", "K2", "java.lang.Integer")));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("PagedA", compositeTable, null));
        var field = queryInterfaceConnectionField("compositeConnection", participants, 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "compositeConnection").code().toString();
        assertThat(body)
            .as("sortField is typed as Field<JSONB> for composite-PK participants")
            .contains("org.jooq.Field<org.jooq.JSONB> sortField")
            .contains("org.jooq.JSONB.class");
    }

    @Test
    void queryInterfaceField_connection_compositePkParticipant_perTypenameHelperProjectsJsonbArraySortKey() {
        // The per-typename stage-2 helper must project DSL.jsonbArray(pk0..pkN) under the
        // __sort__ alias on each typed Record so ConnectionHelper.encodeCursor reads the same
        // JSONB value the stage-1 sort key produced. Without this, page-2 cursors would decode
        // back to a partial sort key and the seek predicate would skip or repeat rows.
        var compositeTable = TestFixtures.tableRef("paged_a", "PAGED_A", "PagedA",
            List.of(
                new ColumnRef("k1", "K1", "java.lang.Integer"),
                new ColumnRef("k2", "K2", "java.lang.Integer")));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("PagedA", compositeTable, null));
        var field = queryInterfaceConnectionField("compositeConnection", participants, 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var perTypename = method(spec, "selectPagedAForCompositeConnection").code().toString();
        assertThat(perTypename)
            .as("per-typename helper projects jsonbArray(...) as __sort__ on each typed Record")
            .contains("jsonbArray(")
            .contains(".as(\"__sort__\")");
    }

    @Test
    void queryInterfaceField_connection_compositePkParticipant_stage1ProjectsAllPkColumns() {
        // Stage-1 outer SELECT projects every __pk_i__ column so the dispatch loop has the full
        // PK tuple for ValuesJoinRowBuilder. With pkArity > 1, the loop emits an Object[] of all
        // PK slots; with pkArity == 1, it stays at __pk0__.
        var compositeTable = TestFixtures.tableRef("paged_a", "PAGED_A", "PagedA",
            List.of(
                new ColumnRef("k1", "K1", "java.lang.Integer"),
                new ColumnRef("k2", "K2", "java.lang.Integer")));
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("PagedA", compositeTable, null));
        var field = queryInterfaceConnectionField("compositeConnection", participants, 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "compositeConnection").code().toString();
        assertThat(body)
            .as("stage-1 outer SELECT projects __pk0__ and __pk1__")
            .contains("name(\"__pk0__\")")
            .contains("name(\"__pk1__\")");
        assertThat(body)
            .as("dispatch loop reads all PK slots into Object[]")
            .contains("r.get(\"__pk0__\")")
            .contains("r.get(\"__pk1__\")");
    }

    @Test
    void queryUnionField_connection_emitsSameShapeAsInterfaceField() {
        // Union variant parity: same emitter, same body shape. Ratchet against drift between
        // QueryInterfaceField and QueryUnionField on the connection path the same way the
        // list path does in queryUnionField_emitsTwoStageStructure_likeInterfaceField.
        var field = queryUnionConnectionField("documentsConnection", filmAndActorParticipants(), 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, null, List.of(field),
            DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "documentsConnection").code().toString();
        assertThat(body)
            .contains(".asTable(\"pages\")")
            .contains("ConnectionHelper.pageRequest(")
            .contains(".seek(page.seekFields())")
            .contains(".util.ConnectionResult(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())");
        assertThat(method(spec, "selectFilmForDocumentsConnection").code().toString())
            .contains("FILM_ID.as(\"__sort__\")");
    }

    // ===== ChildField.InterfaceField / ChildField.UnionField (multi-table polymorphic child) =====
    //
    // Same two-stage emission as the Query-rooted case, plus parent correlation: the fetcher
    // registers a DataLoader keyed on the parent table's PK, and the batched rows method JOINs
    // each branch to a parentInput VALUES table via the participant's FK back to the parent.

    private static java.util.Map<String, ParticipantCorrelation> filmActorChildJoinPaths() {
        // film_actor → film via film_actor_film_id_fkey: source columns sit on film_actor side.
        // film_actor → actor via film_actor_actor_id_fkey: same shape. The FK source columns on
        // the parent (FilmActor) side must coincide with FilmActor's PK by sqlName so the
        // batched-connection emitter can resolve each parent PK slot to a real FK target column;
        // the synthetic batched parent PK is a single-column [last_update] (see
        // filmActorParentTableForBatched), so both FKs source from last_update on the parent
        // side. The participant-side targetColumns name and type are immaterial to the unit test
        // (the emitter does not consume them for the parent-input lookup).
        // FK direction: film_actor (source/FK holder) → film (target/PK side) and similarly for actor.
        return java.util.Map.of(
            "Film", TestFixtures.participantFkPath(
                List.of(new ColumnRef("last_update", "LAST_UPDATE", "java.sql.Timestamp")),
                List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"))),
            "Actor", TestFixtures.participantFkPath(
                List.of(new ColumnRef("last_update", "LAST_UPDATE", "java.sql.Timestamp")),
                List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))));
    }

    private static no.sikt.graphitron.rewrite.model.TableRef filmActorParentTableForList() {
        // Single-column PK on FilmActor that doubles as the FK source on the participants' join
        // paths (filmActorChildJoinPaths sources both FKs from last_update). The list
        // arm constructs a SourceKey (Wrap.Row) from a KeyLift.FkColumns lift over the parent PK;
        // aligning the parent PK with the FK source columns lets the emitted JOIN parentInput
        // predicate land on the parent key column.
        return TestFixtures.tableRef("film_actor", "FILM_ACTOR", "FilmActor",
            List.of(new ColumnRef("last_update", "LAST_UPDATE", "java.sql.Timestamp")));
    }

    private static no.sikt.graphitron.rewrite.model.SourceKey filmActorParentSourceKey() {
        return TestFixtures.polymorphicRowParentSourceKey(filmActorParentTableForList().primaryKeyColumns());
    }

    private static no.sikt.graphitron.rewrite.model.GraphitronType.ResultType filmActorParentResultType() {
        return new no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType(
            "FilmActor", null, null, filmActorParentTableForList());
    }

    /** The batched leaves' catalog-FK registration: one key per parent row. */
    private static no.sikt.graphitron.rewrite.model.LoaderRegistration polymorphicRowRegistration(boolean valueIsList) {
        return new no.sikt.graphitron.rewrite.model.LoaderRegistration(valueIsList,
            no.sikt.graphitron.rewrite.model.LoaderRegistration.Container.POSITIONAL_LIST,
            no.sikt.graphitron.rewrite.model.LoaderRegistration.Dispatch.LOAD_ONE);
    }

    private static ChildField childInterfaceField(String parentType, String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                TestFixtures.tableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        if (isList) {
            return new ChildField.BatchedInterfaceField(parentType, name, null,
                returnType, participants, filmActorChildJoinPaths(),
                filmActorParentSourceKey(), TestFixtures.fkColumnsLift(),
                filmActorParentTableForList(), filmActorParentResultType(),
                polymorphicRowRegistration(true));
        }
        return new ChildField.InterfaceField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths(),
            filmActorParentSourceKey(), TestFixtures.fkColumnsLift(),
            filmActorParentTableForList(), filmActorParentResultType());
    }

    private static ChildField childUnionField(String parentType, String name, boolean isList) {
        var wrapper = isList ? (FieldWrapper) nonNullList() : single();
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                TestFixtures.tableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        if (isList) {
            return new ChildField.BatchedUnionField(parentType, name, null,
                returnType, participants, filmActorChildJoinPaths(),
                filmActorParentSourceKey(), TestFixtures.fkColumnsLift(),
                filmActorParentTableForList(), filmActorParentResultType(),
                polymorphicRowRegistration(true));
        }
        return new ChildField.UnionField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths(),
            filmActorParentSourceKey(), TestFixtures.fkColumnsLift(),
            filmActorParentTableForList(), filmActorParentResultType());
    }

    @Test
    void childInterfaceField_listForm_emitsOneDataLoaderRegistrationAndOneRowsMethod() {
        // List-arm child fetcher registers a DataLoader keyed on the parent table's PK
        // (Row1<Timestamp> here) and delegates to a rows<Field>(keys, env) batch loader. The
        // main fetcher contains no per-parent env.getSource() read against participant tables;
        // all parent-side reads happen inside the key-extraction helper exactly once.
        var field = childInterfaceField("FilmActor", "related", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForList(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var fetcher = method(spec, "related").code().toString();
        assertThat(fetcher)
            .as("DataLoader name uses path-only key (R190 dropped the tenant prefix)")
            .contains("env.getExecutionStepInfo().getPath().getKeysOnly()");
        assertThat(fetcher)
            .as("Loader value type is List<Record> per parent")
            .contains("org.dataloader.DataLoader<org.jooq.Row1<java.sql.Timestamp>, java.util.List<org.jooq.Record>>")
            .contains("DataLoaderFactory.newDataLoader");
        assertThat(fetcher)
            .as("Loader.load(key, env) tail with thenApply + exceptionally routing through the "
                + "no-channel disposition (surfaceClientErrorOrRedact, R415)")
            .contains("loader.load(key, env)")
            .contains(".thenApply(payload -> graphql.execution.DataFetcherResult.")
            .contains(".exceptionally(t -> ")
            .contains("ErrorRouter.surfaceClientErrorOrRedact(t, env)");
        // Exactly one rows method emitted alongside the fetcher.
        var rowsMethod = method(spec, "rowsRelated");
        assertThat(rowsMethod).as("paired rows<Field> batch loader exists").isNotNull();
        // Rows method takes List<RowN<…>> and returns List<List<Record>>.
        assertThat(rowsMethod.code().toString())
            .as("rows method emits a parentInput VALUES table and unions per-branch SELECTs")
            .contains("parentInput")
            .contains("DSL.values(parentRows)")
            .contains(".join(parentInput).on(");
    }

    @Test
    void childInterfaceField_listForm_keyTupleArityMatchesParentPk() {
        // Single-PK parent collapses to Row1<Timestamp>; composite-PK parent widens to RowN.
        var singleField = childInterfaceField("FilmActor", "related", true);
        var singleSpec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForList(), null, List.of(singleField), DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(singleSpec, "related").code().toString())
            .contains("org.dataloader.DataLoader<org.jooq.Row1<java.sql.Timestamp>");

        var compositeField = compositePkChildListField();
        var compositeSpec = TypeFetcherGenerator.generateTypeSpec("Project",
            compositePkParentTable(), null, List.of(compositeField), DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(compositeSpec, "items").code().toString())
            .as("Composite parent PK widens key element to Row2<Integer, Integer>")
            .contains("org.dataloader.DataLoader<org.jooq.Row2<java.lang.Integer, java.lang.Integer>");
    }

    @Test
    void childUnionField_listForm_emitsSameDataLoaderShapeAsInterfaceField() {
        // UnionField shares MultiTablePolymorphicEmitter with InterfaceField; body shape is
        // identical apart from the participant-list source. Pin the equivalence so a future
        // drift in either path fails fast.
        var ifaceSpec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForList(), null,
            List.of(childInterfaceField("FilmActor", "related", true)), DEFAULT_OUTPUT_PACKAGE);
        var unionSpec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForList(), null,
            List.of(childUnionField("FilmActor", "related", true)), DEFAULT_OUTPUT_PACKAGE);
        var ifaceFetcher = method(ifaceSpec, "related").code().toString();
        var unionFetcher = method(unionSpec, "related").code().toString();
        assertThat(unionFetcher).isEqualTo(ifaceFetcher);
        var ifaceRows = method(ifaceSpec, "rowsRelated").code().toString();
        var unionRows = method(unionSpec, "rowsRelated").code().toString();
        assertThat(unionRows).isEqualTo(ifaceRows);
    }

    @Test
    void childInterfaceField_listForm_routesParentKeyExtractionThroughBuildRecordParentKeyExtraction() {
        // Structural pin: the emitted key extraction uses the canonical four-shape helper
        // (DSL.row(((Record) env.getSource()).get(...))) rather than an inline cast-then-read
        // path that bypasses the helper. Mirrors the connection-arm assertion below.
        var listSpec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForList(), null,
            List.of(childInterfaceField("FilmActor", "related", true)), DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(listSpec, "related").code().toString())
            .as("DSL.row(...) is the canonical helper's emit shape")
            .contains("org.jooq.Row1<java.sql.Timestamp> key = org.jooq.impl.DSL.row(((org.jooq.Record) env.getSource()).get(no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE))");

        var connSpec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(), null,
            List.of(childInterfaceConnectionField("FilmActor", "relatedConnection", 5)), DEFAULT_OUTPUT_PACKAGE);
        assertThat(method(connSpec, "relatedConnection").code().toString())
            .as("Connection arm shares the canonical helper pattern; same key shape")
            .contains("org.jooq.Row1<java.sql.Timestamp> key = org.jooq.impl.DSL.row(((org.jooq.Record) env.getSource()).get(no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE))");
    }

    private static ChildField.BatchedInterfaceField compositePkChildListField() {
        var wrapper = new FieldWrapper.List(false, false);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("ProjectItem", wrapper);
        var note = compositeFkParticipant("project_note", "PROJECT_NOTE", "ProjectNote", "note_id", "NOTE_ID");
        var event = compositeFkParticipant("project_event", "PROJECT_EVENT", "ProjectEvent", "event_id", "EVENT_ID");
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("ProjectNote", note, null),
            new ParticipantRef.TableBound("ProjectEvent", event, null));
        var parentTable = compositePkParentTable();
        var parentSourceKey = TestFixtures.polymorphicRowParentSourceKey(parentTable.primaryKeyColumns());
        var parentResultType = (no.sikt.graphitron.rewrite.model.GraphitronType.ResultType)
            new no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType(
                "Project", null, null, parentTable);
        return new ChildField.BatchedInterfaceField("Project", "items", null,
            returnType, participants, compositePkParentJoinPaths(),
            parentSourceKey, TestFixtures.fkColumnsLift(), parentTable, parentResultType,
            polymorphicRowRegistration(true));
    }

    @Test
    void childInterfaceField_isImplementedLeaf_notInNotImplementedReasons() {
        // Both delivery halves of the polymorphic child pair are IMPLEMENTED_LEAVES,
        // not STUBBED_VARIANTS.
        assertThat(TypeFetcherGenerator.IMPLEMENTED_LEAVES)
            .contains(ChildField.InterfaceField.class, ChildField.UnionField.class,
                ChildField.BatchedInterfaceField.class, ChildField.BatchedUnionField.class);
        assertThat(TypeFetcherGenerator.STUBBED_VARIANTS)
            .doesNotContainKeys(ChildField.InterfaceField.class, ChildField.UnionField.class,
                ChildField.BatchedInterfaceField.class, ChildField.BatchedUnionField.class);
    }

    // ===== Connection pagination on ChildField.InterfaceField / ChildField.UnionField =====
    //
    // DataLoader-batched: one polymorphic UNION ALL JOINed to a parentInput VALUES table, ranked
    // per parent with ROW_NUMBER() OVER (PARTITION BY __idx__), then scattered into per-parent
    // buckets. Each bucket's ConnectionResult carries the shared pagesTable plus idxField.eq(i),
    // so totalCount counts only that parent's occupants.

    private static ChildField.BatchedInterfaceField childInterfaceConnectionField(
            String parentType, String name, int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                TestFixtures.tableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        return new ChildField.BatchedInterfaceField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths(),
            filmActorParentSourceKey(), TestFixtures.fkColumnsLift(),
            filmActorParentTableForList(), filmActorParentResultType(),
            polymorphicRowRegistration(false));
    }

    private static ChildField childUnionConnectionField(
            String parentType, String name, int defaultPageSize) {
        var wrapper = new FieldWrapper.Connection(false, defaultPageSize);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("FilmOrActor", wrapper);
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("Film", filmTableWithPk(), null),
            new ParticipantRef.TableBound("Actor",
                TestFixtures.tableRef("actor", "ACTOR", "Actor",
                    List.of(new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"))),
                null));
        return new ChildField.BatchedUnionField(parentType, name, null,
            returnType, participants, filmActorChildJoinPaths(),
            filmActorParentSourceKey(), TestFixtures.fkColumnsLift(),
            filmActorParentTableForList(), filmActorParentResultType(),
            polymorphicRowRegistration(false));
    }

    /**
     * Parent table fixture with a synthetic single-column PK on film_actor, exercising the
     * DataLoader-batched windowed CTE path. The actual sakila.film_actor PK is composite
     * (film_id, actor_id), but the unit tests don't run real SQL; they only assert generator
     * output, and a single-column PK keeps the batched path engaged.
     */
    private static TableRef filmActorParentTableForBatched() {
        return TestFixtures.tableRef("film_actor", "FILM_ACTOR", "FilmActor",
            List.of(new ColumnRef("last_update", "LAST_UPDATE", "java.sql.Timestamp")));
    }

    @Test
    void childInterfaceField_connection_emitsDataLoaderRegisteringFetcher() {
        // Child connection fetcher registers a DataLoader keyed on the parent table's
        // single-column PK (Row1<Timestamp> here) and delegates to a rows<Field>(keys, env)
        // batch loader.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var body = method(spec, "relatedConnection").code().toString();
        assertThat(body)
            .as("DataLoader name: path-only key (R190 dropped the tenant prefix)")
            .contains("env.getExecutionStepInfo().getPath().getKeysOnly()");
        assertThat(body)
            .as("DataLoader<Row1<Timestamp>, ConnectionResult> registration")
            .contains("org.jooq.Row1<java.sql.Timestamp>")
            .contains("DataLoaderFactory.newDataLoader");
        assertThat(body)
            .as("Parent PK extraction delegated to GeneratorUtils.buildRecordParentKeyExtraction "
                + "(inline DSL.row over env.getSource() reads — no fk0/fk1 locals)")
            .contains("org.jooq.Row1<java.sql.Timestamp> key = org.jooq.impl.DSL.row("
                + "((org.jooq.Record) env.getSource()).get(no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE))");
        assertThat(body)
            .as("Async tail: thenApply lifts ConnectionResult into DataFetcherResult, exceptionally "
                + "routes through the no-channel disposition (surfaceClientErrorOrRedact, R415)")
            .contains("loader.load(key, env)")
            .contains(".thenApply(payload -> graphql.execution.DataFetcherResult.")
            .contains(".exceptionally(t -> ")
            .contains("ErrorRouter.surfaceClientErrorOrRedact(t, env)");
    }

    @Test
    void childInterfaceField_connection_emitsBatchedRowsMethod() {
        // The rows method takes List<Row1<PK>> + env and returns List<ConnectionResult>; one
        // ConnectionResult per parent in the batch.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var rows = method(spec, "rowsRelatedConnection").code().toString();
        assertThat(rows)
            .as("empty-input short-circuit")
            .contains("if (keys.isEmpty())")
            .contains("return java.util.List.of();");
        assertThat(rows)
            .as("typed parentInput VALUES table")
            .contains("org.jooq.Row2<java.lang.Integer, java.sql.Timestamp>[] parentRows")
            .contains("org.jooq.impl.DSL.values(parentRows).as(\"parentInput\", \"idx\", \"last_update\")");
        assertThat(rows)
            .as("each parent row rebinds the RowN key cell at the owner column's Converter DataType (R413)")
            .contains("org.jooq.impl.DSL.row(org.jooq.impl.DSL.inline(i), "
                + "org.jooq.impl.DSL.val(parentKeyCellValue(k.field1()), "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE.getDataType()))");
    }

    @Test
    void childInterfaceField_connection_appliesParentFkJoinPerBranch() {
        // Each stage-1 branch JOINs parentInput on the participant FK column. Both branches
        // must JOIN independently; without per-branch JOIN the union would return
        // cross-product noise.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var rows = method(spec, "rowsRelatedConnection").code().toString();
        assertThat(rows)
            .as("Film branch JOINs parentInput on FILM_ID")
            .contains("stage1_Film.FILM_ID.eq(parentInput.field(\"last_update\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE.getDataType()))");
        assertThat(rows)
            .as("Actor branch JOINs parentInput on ACTOR_ID")
            .contains("stage1_Actor.ACTOR_ID.eq(parentInput.field(\"last_update\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE.getDataType()))");
        assertThat(rows)
            .as("each branch projects parentInput.field(0) as __idx__")
            .contains("parentInput.field(0, java.lang.Integer.class).as(\"__idx__\")");
    }

    @Test
    void childInterfaceField_connection_emitsRankedCteWithRowNumber() {
        // The windowed CTE wraps pagesTable in a ROW_NUMBER() OVER (PARTITION BY __idx__
        // ORDER BY effectiveOrderBy) envelope; the outer SELECT filters __rn__ <= page.limit()
        // for per-partition limit.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var rows = method(spec, "rowsRelatedConnection").code().toString();
        assertThat(rows)
            .as("pagesTable derived table")
            .contains(".asTable(\"pages\")");
        assertThat(rows)
            .as("ranked CTE with ROW_NUMBER over PARTITION BY __idx__")
            .contains("org.jooq.impl.DSL.rowNumber().over(org.jooq.impl.DSL.partitionBy(idxField).orderBy(page.effectiveOrderBy())).as(\"__rn__\")")
            .contains(".asTable(\"ranked\")");
        assertThat(rows)
            .as("ranked CTE applies orderBy + seek BEFORE rn is computed")
            .contains(".orderBy(page.effectiveOrderBy())")
            .contains(".seek(page.seekFields())");
        assertThat(rows)
            .as("outer SELECT filters __rn__ <= page.limit()")
            .contains("ranked.field(\"__rn__\", java.lang.Integer.class).le(org.jooq.impl.DSL.val(page.limit()))");
    }

    @Test
    void childInterfaceField_connection_perParentConnectionResultsCarryIdxCondition() {
        // The scatter loop wraps each per-parent bucket in ConnectionResult(bucket, page,
        // pagesTable, idxField.eq(i)). totalCount per parent runs SELECT count(*) FROM pages
        // WHERE __idx__ = :i.
        var field = childInterfaceConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var rows = method(spec, "rowsRelatedConnection").code().toString();
        assertThat(rows)
            .as("parallel parentIdxByOuter array populated from stage1's __idx__")
            .contains("int[] parentIdxByOuter = new int[stage1.size()]")
            .contains("parentIdxByOuter[outerIdx] = r.get(\"__idx__\", java.lang.Integer.class)");
        assertThat(rows)
            .as("scatter into per-parent buckets keyed by parentIdxByOuter")
            .contains("buckets.get(parentIdxByOuter[outerIdx]).add(r)");
        assertThat(rows)
            .as("per-parent ConnectionResult carries shared pagesTable + idxField.eq(i)")
            .contains(".util.ConnectionResult(buckets.get(i), page, pagesTable, idxField.eq(i))");
    }

    @Test
    void childUnionField_connection_emitsSameShapeAsChildInterface() {
        // Union variant parity: same emitter, same body shape under the batched connection path.
        var field = childUnionConnectionField("FilmActor", "relatedConnection", 5);
        var spec = TypeFetcherGenerator.generateTypeSpec("FilmActor",
            filmActorParentTableForBatched(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var fetcher = method(spec, "relatedConnection").code().toString();
        var rows = method(spec, "rowsRelatedConnection").code().toString();
        assertThat(fetcher)
            .as("union variant: same DataLoader-registering main fetcher")
            .contains("DataLoaderFactory.newDataLoader")
            .contains("loader.load(key, env)");
        assertThat(rows)
            .as("union variant: same JOIN parentInput per branch")
            .contains("stage1_Film.FILM_ID.eq(parentInput.field(\"last_update\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE.getDataType()))")
            .contains("stage1_Actor.ACTOR_ID.eq(parentInput.field(\"last_update\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.FILM_ACTOR.LAST_UPDATE.getDataType()))");
        assertThat(rows)
            .as("union variant: same per-parent ConnectionResult with idxField.eq(i)")
            .contains(".util.ConnectionResult(buckets.get(i), page, pagesTable, idxField.eq(i))");
    }

    // ===== Composite-PK parent (RowN widening) =====
    //
    // Synthetic fixture: parent type "Project" backed by table "project" with a 2-column PK
    // (org_id, project_id). Two participants with FKs pointing back to that composite key,
    // each with single-column PK so the connection-mode validator (single-PK participant)
    // remains satisfied. Exercises Row1<...> → RowN<...> widening: DataLoader key element
    // becomes Row2<Integer, Integer>; parentInput VALUES becomes Row3<Integer, Integer,
    // Integer> (idx + parent PK arity 2); per-branch JOIN ON has an AND-chain across the
    // composite FK.

    private static TableRef compositePkParentTable() {
        return TestFixtures.tableRef("project", "PROJECT", "Project",
            List.of(
                new ColumnRef("org_id", "ORG_ID", "java.lang.Integer"),
                new ColumnRef("project_id", "PROJECT_ID", "java.lang.Integer")));
    }

    private static TableRef compositeFkParticipant(String tableName, String tableUpper, String typeName, String pkSqlName, String pkUpper) {
        return TestFixtures.tableRef(tableName, tableUpper, typeName,
            List.of(new ColumnRef(pkSqlName, pkUpper, "java.lang.Integer")));
    }

    private static java.util.Map<String, ParticipantCorrelation> compositePkParentJoinPaths() {
        // FK: ProjectNote.(org_id, project_id) -> Project.(org_id, project_id), composite and
        // position-aligned; org_id/project_id coincide on both sides.
        var pair = List.of(
            new ColumnRef("org_id", "ORG_ID", "java.lang.Integer"),
            new ColumnRef("project_id", "PROJECT_ID", "java.lang.Integer"));
        return java.util.Map.of(
            "ProjectNote", TestFixtures.participantFkPath(pair, pair),
            "ProjectEvent", TestFixtures.participantFkPath(pair, pair));
    }

    private static ChildField.BatchedInterfaceField compositePkChildInterfaceConnectionField() {
        var wrapper = new FieldWrapper.Connection(false, 5);
        var returnType = new ReturnTypeRef.PolymorphicReturnType("ProjectItem", wrapper);
        var note = compositeFkParticipant("project_note", "PROJECT_NOTE", "ProjectNote", "note_id", "NOTE_ID");
        var event = compositeFkParticipant("project_event", "PROJECT_EVENT", "ProjectEvent", "event_id", "EVENT_ID");
        var participants = List.<ParticipantRef>of(
            new ParticipantRef.TableBound("ProjectNote", note, null),
            new ParticipantRef.TableBound("ProjectEvent", event, null));
        var parentTable = compositePkParentTable();
        var parentSourceKey = TestFixtures.polymorphicRowParentSourceKey(parentTable.primaryKeyColumns());
        var parentResultType = (no.sikt.graphitron.rewrite.model.GraphitronType.ResultType)
            new no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType(
                "Project", null, null, parentTable);
        return new ChildField.BatchedInterfaceField("Project", "itemsConnection", null,
            returnType, participants, compositePkParentJoinPaths(),
            parentSourceKey, TestFixtures.fkColumnsLift(), parentTable, parentResultType,
            polymorphicRowRegistration(false));
    }

    @Test
    void childInterfaceField_connection_compositePkParent_widensKeyToRowN() {
        // The DataLoader key element is Row2<Integer, Integer> (parent PK arity 2). The
        // computeIfAbsent and load(key, env) shape is unchanged; only the type widens.
        var field = compositePkChildInterfaceConnectionField();
        var spec = TypeFetcherGenerator.generateTypeSpec("Project",
            compositePkParentTable(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var fetcher = method(spec, "itemsConnection").code().toString();
        assertThat(fetcher)
            .as("DataLoader<Row2<Integer, Integer>, ConnectionResult>")
            .contains("org.dataloader.DataLoader<org.jooq.Row2<java.lang.Integer, java.lang.Integer>");
        assertThat(fetcher)
            .as("Composite parent-PK extraction emits an inline DSL.row over both columns "
                + "(no fk0/fk1 locals — buildRecordParentKeyExtraction emits a single statement)")
            .contains("org.jooq.Row2<java.lang.Integer, java.lang.Integer> key = org.jooq.impl.DSL.row("
                + "((org.jooq.Record) env.getSource()).get(no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.ORG_ID), "
                + "((org.jooq.Record) env.getSource()).get(no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.PROJECT_ID))");
    }

    @Test
    void childInterfaceField_connection_compositePkParent_widensParentInputToRowN() {
        // parentInput VALUES table widens to Row3<Integer, Integer, Integer> (idx + parent PK
        // arity 2) and aliases ("parentInput", "idx", "org_id", "project_id"). Each parent row
        // is built via DSL.row(DSL.inline(i), k.field1(), k.field2()).
        var field = compositePkChildInterfaceConnectionField();
        var spec = TypeFetcherGenerator.generateTypeSpec("Project",
            compositePkParentTable(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var rows = method(spec, "rowsItemsConnection").code().toString();
        assertThat(rows)
            .as("Row3<Integer, Integer, Integer>[] parentRows for idx + 2 parent PK columns")
            .contains("org.jooq.Row3<java.lang.Integer, java.lang.Integer, java.lang.Integer>[] parentRows");
        assertThat(rows)
            .as("DSL.row rebinds each composite key cell at the owner column's Converter DataType (R413)")
            .contains("org.jooq.impl.DSL.row(org.jooq.impl.DSL.inline(i), "
                + "org.jooq.impl.DSL.val(parentKeyCellValue(k.field1()), "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.ORG_ID.getDataType()), "
                + "org.jooq.impl.DSL.val(parentKeyCellValue(k.field2()), "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.PROJECT_ID.getDataType()))");
        assertThat(rows)
            .as("parentInput.as carries both parent PK column SQL names")
            .contains("org.jooq.impl.DSL.values(parentRows).as(\"parentInput\", \"idx\", \"org_id\", \"project_id\")");
    }

    @Test
    void childInterfaceField_connection_compositePkParent_widensJoinPredicateToAndChain() {
        // Per-branch JOIN ON predicate is an AND-chain across parent PK slots: each composite
        // FK column on the participant equals its position-paired parentInput VALUES column.
        var field = compositePkChildInterfaceConnectionField();
        var spec = TypeFetcherGenerator.generateTypeSpec("Project",
            compositePkParentTable(),
            null, List.of(field), DEFAULT_OUTPUT_PACKAGE);
        var rows = method(spec, "rowsItemsConnection").code().toString();
        assertThat(rows)
            .as("ProjectNote branch JOINs on (org_id AND project_id)")
            .contains("stage1_ProjectNote.ORG_ID.eq(parentInput.field(\"org_id\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.ORG_ID.getDataType()))"
                + ".and(stage1_ProjectNote.PROJECT_ID.eq(parentInput.field(\"project_id\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.PROJECT_ID.getDataType())))");
        assertThat(rows)
            .as("ProjectEvent branch JOINs on (org_id AND project_id) — same AND-chain shape")
            .contains("stage1_ProjectEvent.ORG_ID.eq(parentInput.field(\"org_id\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.ORG_ID.getDataType()))"
                + ".and(stage1_ProjectEvent.PROJECT_ID.eq(parentInput.field(\"project_id\", "
                + "no.sikt.graphitron.rewrite.test.jooq.Tables.PROJECT.PROJECT_ID.getDataType())))");
    }

    // ===== InputBean helper emission =====

    @Test
    void inputBeanInstantiationEmitter_singularHelper_signatureAndBody() {
        var bean = ClassName.get("com.example", "Foo");
        var ib = new CallSiteExtraction.InputBean(bean,
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "title", "title", new CallSiteExtraction.Direct(), false, "java.lang.String")));
        var spec = InputBeanInstantiationEmitter.buildSingularHelper(ib);
        assertThat(spec.name()).isEqualTo("createFoo");
        assertThat(spec.returnType().toString()).isEqualTo("com.example.Foo");
        assertThat(spec.parameters()).hasSize(1);
        assertThat(spec.parameters().get(0).type().toString()).isEqualTo("java.util.Map<java.lang.String, java.lang.Object>");
        var body = spec.code().toString();
        assertThat(body).contains("if (raw == null) return null");
        assertThat(body).contains("new com.example.Foo(title)");
    }

    @Test
    void inputBeanInstantiationEmitter_pluralHelper_signatureAndBody() {
        var bean = ClassName.get("com.example", "Foo");
        var ib = new CallSiteExtraction.InputBean(bean,
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "title", "title", new CallSiteExtraction.Direct(), false, "java.lang.String")));
        var spec = InputBeanInstantiationEmitter.buildPluralHelper(ib, ClassName.get("com.example", "FooFetchers"));
        assertThat(spec.name()).isEqualTo("createFooList");
        assertThat(spec.returnType().toString()).isEqualTo("java.util.List<com.example.Foo>");
        assertThat(spec.parameters()).hasSize(1);
        assertThat(spec.parameters().get(0).type().toString()).isEqualTo("java.lang.Object");
        var body = spec.code().toString();
        assertThat(body).contains("if (raw == null) return null");
        assertThat(body).contains("createFoo(m)");
    }

    @Test
    void inputBeanInstantiationEmitter_javaBean_usesNoArgPlusSetters() {
        var bean = ClassName.get("com.example", "Foo");
        var ib = new CallSiteExtraction.InputBean(bean,
            CallSiteExtraction.InputBean.Target.JAVA_BEAN,
            List.of(new CallSiteExtraction.FieldBinding(
                "title", "title", new CallSiteExtraction.Direct(), false, "java.lang.String")));
        var body = InputBeanInstantiationEmitter.buildSingularHelper(ib).code().toString();
        assertThat(body).contains("com.example.Foo bean = new com.example.Foo()");
        assertThat(body).contains("bean.setTitle(title)");
        assertThat(body).contains("return bean");
    }

    @Test
    void inputBeanInstantiationEmitter_recordSingularHelper_boxedPrimitiveFieldEmitsWrapperCast() {
        // A record component typed `int` reaches the emitter as
        // javaElementTypeName = "java.lang.Integer". The emitter must succeed (ClassName.bestGuess
        // accepts the wrapper FQN), declare an Integer-typed local, cast raw.get(...) to Integer,
        // and pass the local positionally to the canonical record constructor (which autoboxes).
        var bean = ClassName.get("com.example", "Foo");
        var ib = new CallSiteExtraction.InputBean(bean,
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "n", "n", new CallSiteExtraction.Direct(), false, "java.lang.Integer")));
        var body = InputBeanInstantiationEmitter.buildSingularHelper(ib).code().toString();
        assertThat(body).contains("java.lang.Integer n = (java.lang.Integer) raw.get(\"n\")");
        assertThat(body).contains("new com.example.Foo(n)");
    }

    @Test
    void inputBeanInstantiationEmitter_javaBeanSingularHelper_boxedPrimitiveFieldEmitsWrapperCast() {
        // JavaBean mirror of the record case. A `void setActive(boolean)`
        // setter reaches the emitter as javaElementTypeName = "java.lang.Boolean". The emitter must
        // declare a Boolean local, cast raw.get(...) to Boolean, and pass the local to setActive
        // (which auto-unboxes to the primitive boolean parameter).
        var bean = ClassName.get("com.example", "Foo");
        var ib = new CallSiteExtraction.InputBean(bean,
            CallSiteExtraction.InputBean.Target.JAVA_BEAN,
            List.of(new CallSiteExtraction.FieldBinding(
                "active", "active", new CallSiteExtraction.Direct(), false, "java.lang.Boolean")));
        var body = InputBeanInstantiationEmitter.buildSingularHelper(ib).code().toString();
        assertThat(body).contains("java.lang.Boolean active = (java.lang.Boolean) raw.get(\"active\")");
        assertThat(body).contains("bean.setActive(active)");
    }

    @Test
    void inputBeanInstantiationEmitter_collectTransitively_dedupNestedBeans() {
        var inner = new CallSiteExtraction.InputBean(
            ClassName.get("com.example", "Inner"),
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "k", "k", new CallSiteExtraction.Direct(), false, "java.lang.String")));
        var outer = new CallSiteExtraction.InputBean(
            ClassName.get("com.example", "Outer"),
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "nested", "nested", inner, true, "com.example.Inner")));
        var out = new java.util.LinkedHashMap<ClassName, CallSiteExtraction.InputBean>();
        InputBeanInstantiationEmitter.collectTransitively(outer, out);
        // A repeat call must not duplicate either entry.
        InputBeanInstantiationEmitter.collectTransitively(outer, out);
        assertThat(out.keySet()).extracting(ClassName::simpleName).containsExactly("Outer", "Inner");
    }
}
