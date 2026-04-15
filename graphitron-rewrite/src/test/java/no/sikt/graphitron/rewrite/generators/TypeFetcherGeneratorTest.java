package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TypeFetcherGenerator}. Tests verify structural properties of the
 * generated TypeSpec (method names, return types, parameter signatures) — not the generated code
 * body. Code correctness is verified by compiling and executing the generated output in the
 * {@code graphitron-rewrite-test-spec} module.
 */
class TypeFetcherGeneratorTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE);
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    private static GraphitronField columnField(String name, String columnName, String javaName, String columnClass) {
        return new ChildField.ColumnField("Film", name, null, columnName,
            new ColumnRef(columnName, javaName, columnClass), false);
    }

    private static GraphitronField queryTableField(String name, boolean isList) {
        var wrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(false, false)
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
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
    void generate_alwaysHasWiringMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("wiring");
    }

    // ===== Stub methods (column field without parentTable falls to stub) =====

    @Test
    void stub_hasEnvParameter() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void stub_returnsObject() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").returnType().toString())
            .isEqualTo("java.lang.Object");
    }

    @Test
    void stub_bodyThrowsUnsupportedOperationException() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").code().toString()).contains("UnsupportedOperationException");
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
    void columnFetcher_withParentTable_onlyWiringMethod() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("wiring");
    }

    // ===== QueryTableField =====

    @Test
    void queryTableField_list_returnsResultRecord() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void queryTableField_single_returnsRecord() {
        var field = queryTableField("film", false);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "film").returnType().toString())
            .isEqualTo("org.jooq.Record");
    }

    @Test
    void queryTableField_hasEnvParameter() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void queryTableField_isNotStub() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").code().toString()).doesNotContain("UnsupportedOperationException");
    }

    // ===== wiring() method =====

    @Test
    void wiring_signature() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(method(spec, "wiring").returnType().toString())
            .isEqualTo("graphql.schema.idl.TypeRuntimeWiring.Builder");
    }

    @Test
    void wiring_noFields_noDataFetchers() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(method(spec, "wiring").code().toString()).doesNotContain("dataFetcher(");
    }

    @Test
    void wiring_columnField_usesColumnFetcher() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        var wiringCode = method(spec, "wiring").code().toString();
        assertThat(wiringCode).contains("ColumnFetcher");
        assertThat(wiringCode).contains("Tables.FILM.TITLE");
    }

    @Test
    void wiring_queryField_usesPlainMethodReference() {
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null,
            List.of(queryTableField("films", true)));
        var wiringCode = method(spec, "wiring").code().toString();
        assertThat(wiringCode).contains("QueryFetchers::films");
        assertThat(wiringCode).doesNotContain("LightDataFetcher");
    }

    @Test
    void wiring_registersAllFields() {
        var fields = List.<GraphitronField>of(
            columnField("title", "title", "TITLE", "java.lang.String"),
            columnField("releaseYear", "release_year", "RELEASE_YEAR", "java.lang.Integer"));
        var spec = TypeFetcherGenerator.generateTypeSpec("Film", FILM_TABLE, fields);
        var wiringCode = method(spec, "wiring").code().toString();
        assertThat(wiringCode).contains("dataFetcher(\"title\"");
        assertThat(wiringCode).contains("dataFetcher(\"releaseYear\"");
    }

    // ===== QueryLookupTableField =====

    private static GraphitronField lookupQueryField(String name, List<BodyParam> bodyParams) {
        var wrapper = new FieldWrapper.List(false, false);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
        var callParams = bodyParams.stream()
            .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list()))
            .toList();
        var filter = new GeneratedConditionFilter(
            "fake.code.generated.rewrite.types.FilmConditions",
            name + "Condition",
            FILM_TABLE,
            callParams,
            bodyParams);
        return new QueryField.QueryLookupTableField("Query", name, null, returnType,
            List.of(filter), new OrderBySpec.None(), null);
    }

    private static BodyParam listKeyParam(String name, String javaName, String javaType) {
        return new BodyParam(name, new ColumnRef(name, javaName, javaType), javaType, false, true,
            new CallSiteExtraction.Direct());
    }

    private static BodyParam scalarKeyParam(String name, String javaName, String javaType) {
        return new BodyParam(name, new ColumnRef(name, javaName, javaType), javaType, false, false,
            new CallSiteExtraction.Direct());
    }

    private static BodyParam listIdKeyParam(String name, String javaName, String javaType) {
        return new BodyParam(name, new ColumnRef(name, javaName, javaType), javaType, false, true,
            new CallSiteExtraction.JooqConvert(javaName));
    }

    @Test
    void queryLookupField_dataFetcher_returnsResultRecord() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
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
    void queryLookupField_dataFetcher_delegatesToRowsMethod() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").code().toString()).contains("lookupFilmById");
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
    void queryLookupField_rowsMethod_hasEnvParameter() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "lookupFilmById").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void queryLookupField_rowsMethod_isNotStub() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "lookupFilmById").code().toString())
            .doesNotContain("UnsupportedOperationException");
    }

    @Test
    void queryLookupField_scalarKey_isNotStub() {
        var fields = List.of(
            listKeyParam("customer_id", "CUSTOMER_ID", "java.lang.Integer"),
            scalarKeyParam("store_id", "STORE_ID", "java.lang.Integer"));
        var field = lookupQueryField("customerById", fields);
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "customerById").code().toString())
            .doesNotContain("UnsupportedOperationException");
    }

    @Test
    void queryLookupField_idListKey_usesJooqConvertInRowsMethod() {
        var field = lookupQueryField("filmById", List.of(listIdKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "lookupFilmById").code().toString()).contains("getDataType()");
    }

    // ===== @splitQuery TableField =====

    private static final TableRef LANGUAGE_TABLE = new TableRef("language", "LANGUAGE", "Language",
        List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")));

    private static GraphitronField splitQueryField(String parentType, String name) {
        return new ChildField.SplitTableField(parentType, name, null,
            new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, new FieldWrapper.List(false, false)),
            List.of(), List.of(), new OrderBySpec.None(), null,
            new BatchKey.RowKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer"))));
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
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
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
    void splitQuery_rowsMethodTakesTypedKeyList() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.util.List<org.jooq.Row1<java.lang.Integer>>");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("sources");
    }

    // ===== @service field with TableBoundReturnType =====

    private static GraphitronField serviceField(String parentType, String name, boolean isList) {
        var returnWrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(true, true)
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, returnWrapper);
        var method = new MethodRef(
            "no.example.FilmService", "getFilms", "java.util.List",
            List.of(
                new MethodRef.Param.Sourced("keys",
                    new BatchKey.RowKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")))),
                new MethodRef.Param.Typed("filter", "java.lang.String", new ParamSource.Arg()),
                new MethodRef.Param.Typed("tenantId", "java.lang.String", new ParamSource.Context())
            )
        );
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method);
    }

    private static TypeSpec specWithServiceField(String parentType, String fieldName, boolean isList) {
        return TypeFetcherGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE,
            List.of(serviceField(parentType, fieldName, isList)));
    }

    @Test
    void serviceField_list_dataFetcherReturnsCompletableFutureListRecord() {
        assertThat(method(specWithServiceField("Language", "films", true), "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_single_dataFetcherReturnsCompletableFutureRecord() {
        assertThat(method(specWithServiceField("Language", "film", false), "film").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<org.jooq.Record>");
    }

    @Test
    void serviceField_list_rowsMethodSignature() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("keys", "env", "sel");
    }
}
