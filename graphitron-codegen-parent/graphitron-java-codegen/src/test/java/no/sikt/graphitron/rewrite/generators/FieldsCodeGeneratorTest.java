package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourcesRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

class FieldsCodeGeneratorTest {

    private static final FieldsCodeGenerator GEN = new FieldsCodeGenerator();
    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());
    private static final TableRef LANGUAGE_TABLE = new TableRef("language", "LANGUAGE", "Language",
        List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")));

    @BeforeEach
    void setup() {
        GeneratorConfig.setProperties(
            Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE,
            List.of(), Set.of(), List.of());
    }

    @AfterEach
    void teardown() {
        GeneratorConfig.clear();
    }

    private static GraphitronField columnField(String name, String columnName, String javaName) {
        return new ChildField.ColumnField("Film", name, null, columnName,
            new ColumnRef(columnName, javaName, "java.lang.String"), false);
    }

    private static GraphitronField splitQueryField(String parentType, String name) {
        return new ChildField.SplitTableField(parentType, name, null,
            new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, new FieldWrapper.List(false, false)),
            List.of(), List.of(), new OrderBySpec.None(), null);
    }

    private static GraphitronField serviceField(String parentType, String name, boolean isList) {
        var returnWrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(true, true)
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, returnWrapper);
        var method = new MethodRef(
            "no.example.FilmService", "getFilms", "java.util.List",
            List.of(
                new MethodRef.Param.Sourced("keys", new SourcesRef.RowKeyed(List.of("java.lang.Integer"))),
                new MethodRef.Param.Typed("filter", "java.lang.String", new ParamSource.Arg()),
                new MethodRef.Param.Typed("tenantId", "java.lang.String", new ParamSource.Context())
            )
        );
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method);
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameIsTypeNamePlusFields() {
        var spec = GEN.generate("Film", null, List.of());
        assertThat(spec.name()).isEqualTo("FilmFields");
    }

    // ===== Stub fields (no parentTable → falls through to stub) =====

    @Test
    void stubField_signatureAndBody() {
        var spec = GEN.generate("Film", null, List.of(columnField("title", "title", "TITLE")));
        var m = method(spec, "title");
        assertThat(m.returnType().toString()).isEqualTo("java.lang.Object");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("env");
        assertThat(m.code().toString()).contains("UnsupportedOperationException()");
    }

    @Test
    void stubField_multipleFieldsAllPresent() {
        var fields = List.<GraphitronField>of(
            columnField("title", "title", "TITLE"),
            columnField("releaseYear", "release_year", "RELEASE_YEAR"));
        var spec = GEN.generate("Film", null, fields);
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("title", "releaseYear");
    }

    // ===== ColumnField with parentTable → real data fetcher =====

    @Test
    void columnField_readsFromRecord() {
        var fields = List.<GraphitronField>of(columnField("title", "title", "TITLE"));
        var spec = GEN.generate("Film", FILM_TABLE, fields);
        var m = method(spec, "title");
        assertThat(m.returnType().toString()).isEqualTo("java.lang.Object");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("env");
        assertThat(m.code().toString()).contains("env.getSource()");
        assertThat(m.code().toString()).contains("FILM.TITLE");
        assertThat(m.code().toString()).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void columnField_usesCorrectTableAndColumn() {
        var fields = List.<GraphitronField>of(
            columnField("filmId", "film_id", "FILM_ID"));
        var spec = GEN.generate("Film", FILM_TABLE, fields);
        var m = method(spec, "filmId");
        assertThat(m.code().toString()).contains("FILM.FILM_ID");
    }

    // ===== wiring() method =====

    @Test
    void wiring_registersFieldsByMethodReference() {
        var fields = List.<GraphitronField>of(
            columnField("title", "title", "TITLE"),
            columnField("releaseYear", "release_year", "RELEASE_YEAR"));
        var spec = GEN.generate("Film", FILM_TABLE, fields);
        var w = method(spec, "wiring");
        assertThat(w.returnType().toString())
            .isEqualTo("graphql.schema.idl.TypeRuntimeWiring.Builder");
        assertThat(w.code().toString()).contains("newTypeWiring(\"Film\")");
        assertThat(w.code().toString()).contains("dataFetcher(\"title\", FilmFields::title)");
        assertThat(w.code().toString()).contains("dataFetcher(\"releaseYear\", FilmFields::releaseYear)");
    }

    @Test
    void wiring_noFields_noDataFetchers() {
        var spec = GEN.generate("Film", null, List.of());
        var w = method(spec, "wiring");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(");
    }

    // ===== QueryTableField (root query → table) =====

    private static GraphitronField queryTableField(String name, boolean isList,
            java.util.List<WhereFilter> filters, OrderBySpec orderBy) {
        var wrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(false, false)
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
        return new QueryField.QueryTableField("Query", name, null, returnType, filters, orderBy, null);
    }

    @Test
    void queryTableField_list_callsSelectMany() {
        var field = queryTableField("films", true, java.util.List.of(), new OrderBySpec.None());
        var spec = GEN.generate("Query", null, java.util.List.of(field));
        var m = method(spec, "films");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(m.code().toString()).contains("selectMany");
        assertThat(m.code().toString()).contains("noCondition()");
    }

    @Test
    void queryTableField_single_callsSelectOne() {
        var field = queryTableField("film", false, java.util.List.of(), new OrderBySpec.None());
        var spec = GEN.generate("Query", null, java.util.List.of(field));
        var m = method(spec, "film");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Record");
        assertThat(m.code().toString()).contains("selectOne");
    }

    @Test
    void queryTableField_withColumnFilter_buildsCondition() {
        var filter = new WhereFilter.ColumnFilter("id", "Int", true, false,
            new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var field = queryTableField("film", false,
            java.util.List.of(filter), new OrderBySpec.None());
        var spec = GEN.generate("Query", null, java.util.List.of(field));
        var m = method(spec, "film");
        assertThat(m.code().toString()).contains("FILM.FILM_ID.eq(env.<java.lang.Integer>getArgument(\"id\"))");
    }

    @Test
    void queryTableField_withOptionalFilter_guardsNull() {
        var filter = new WhereFilter.ColumnFilter("title", "String", false, false,
            new ColumnRef("title", "TITLE", "java.lang.String"));
        var field = queryTableField("films", true,
            java.util.List.of(filter), new OrderBySpec.None());
        var spec = GEN.generate("Query", null, java.util.List.of(field));
        var m = method(spec, "films");
        assertThat(m.code().toString()).contains("if (env.getArgument(\"title\") != null)");
        assertThat(m.code().toString()).contains("FILM.TITLE.eq(env.<java.lang.String>getArgument(\"title\"))");
    }

    @Test
    void queryTableField_withFixedOrder_buildsSortFields() {
        var order = new OrderBySpec.Fixed(
            java.util.List.of(new OrderBySpec.ColumnOrderEntry(
                new ColumnRef("title", "TITLE", "java.lang.String"), null)),
            "ASC");
        var field = queryTableField("films", true, java.util.List.of(), order);
        var spec = GEN.generate("Query", null, java.util.List.of(field));
        var m = method(spec, "films");
        assertThat(m.code().toString()).contains("FILM.TITLE.asc()");
    }

    // ===== @splitQuery TableField =====

    @Test
    void splitQuery_generatesAsyncFetcherAndRowsMethod() {
        var spec = GEN.generate("Language", null, List.of(splitQueryField("Language", "films")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .contains("films", "rowsFilms", "wiring");
        var fetcher = method(spec, "films");
        assertThat(fetcher.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
        assertThat(fetcher.code().toString()).contains("UnsupportedOperationException()");
    }

    // ===== @service field with TableBoundReturnType =====

    @Test
    void serviceField_list_dataFetcherUsesDataLoader() {
        var spec = specWithServiceField("Language", "films", true);
        var m = method(spec, "films");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
        assertThat(m.code().toString()).contains("computeIfAbsent");
        assertThat(m.code().toString()).contains("newDataLoaderWithContext");
        assertThat(m.code().toString()).contains("LANGUAGE_ID");
    }

    @Test
    void serviceField_single_dataFetcherReturnsSingleRecord() {
        var spec = specWithServiceField("Language", "film", false);
        var m = method(spec, "film");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<org.jooq.Record>");
    }

    @Test
    void serviceField_list_rowsMethodCallsServiceAndDelegates() {
        var spec = specWithServiceField("Language", "films", true);
        var m = method(spec, "loadFilms");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("keys", "dfe", "sel");
        assertThat(m.code().toString()).contains("getArgument(\"filter\")");
        assertThat(m.code().toString()).contains("getContextArgument");
        assertThat(m.code().toString()).contains("getFilms");
        assertThat(m.code().toString()).contains("selectManyByRowKeys");
    }

    @Test
    void serviceField_single_rowsMethodUsesSelectOne() {
        var spec = specWithServiceField("Language", "film", false);
        assertThat(method(spec, "loadFilm").code().toString()).contains("selectOneByRowKeys");
    }

    @Test
    void serviceField_wiringRegistersDataFetcherOnly() {
        var w = method(specWithServiceField("Language", "films", true), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"films\"");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(\"loadFilms\"");
    }

    // ===== Helpers =====

    private static TypeSpec specWithServiceField(String parentType, String fieldName, boolean isList) {
        return GEN.generate(parentType, LANGUAGE_TABLE, List.of(serviceField(parentType, fieldName, isList)));
    }
}
