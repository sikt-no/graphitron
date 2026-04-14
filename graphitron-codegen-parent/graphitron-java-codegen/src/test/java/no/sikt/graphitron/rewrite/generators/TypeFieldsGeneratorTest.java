package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import javax.lang.model.element.Modifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TypeFieldsGenerator}. Tests verify structural properties of the generated
 * TypeSpec (method names, return types, parameter signatures) — not the generated code body.
 * Code correctness is verified by compiling and executing the generated output in the
 * {@code graphitron-rewrite-test-spec} module.
 */
class TypeFieldsGeneratorTest {

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
            new ReturnTypeRef.TableBoundReturnType("Film",
                new TableRef("film", "FILM", "Film", List.of()),
                new FieldWrapper.List(false, false)),
            List.of(), List.of(), new OrderBySpec.None(), null,
            new BatchKey.RowKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer"))));
    }

    private static GraphitronField serviceField(String parentType, String name, boolean isList) {
        var returnWrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(true, true)
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, returnWrapper);
        var method = new MethodRef(
            "no.example.FilmService", "getFilms", "java.util.List",
            List.of(
                new MethodRef.Param.Sourced("keys", new BatchKey.RowKeyed(List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer")))),
                new MethodRef.Param.Typed("filter", "java.lang.String", new ParamSource.Arg()),
                new MethodRef.Param.Typed("tenantId", "java.lang.String", new ParamSource.Context())
            )
        );
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null, method);
    }

    private static GraphitronField queryTableField(String name, boolean isList,
            List<WhereFilter> filters, OrderBySpec orderBy) {
        var wrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(false, false)
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
        return new QueryField.QueryTableField("Query", name, null, returnType, filters, orderBy, null);
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name));
    }

    private static TypeSpec specWithSplitQuery(String parentType, String fieldName) {
        return TypeFieldsGenerator.generateTypeSpec(parentType, null, List.of(splitQueryField(parentType, fieldName)));
    }

    private static TypeSpec specWithServiceField(String parentType, String fieldName, boolean isList) {
        return TypeFieldsGenerator.generateTypeSpec(parentType, LANGUAGE_TABLE, List.of(serviceField(parentType, fieldName, isList)));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameIsTypeNamePlusFields() {
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(spec.name()).isEqualTo("FilmFields");
    }

    // ===== Stub fields (no parentTable → falls through to stub) =====

    @Test
    void stubField_signature() {
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", null, List.of(columnField("title", "title", "TITLE")));
        var m = method(spec, "title");
        assertThat(m.returnType().toString()).isEqualTo("java.lang.Object");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("env");
    }

    @Test
    void stubField_multipleFieldsAllPresent() {
        var fields = List.<GraphitronField>of(
            columnField("title", "title", "TITLE"),
            columnField("releaseYear", "release_year", "RELEASE_YEAR"));
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", null, fields);
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("title", "releaseYear");
    }

    // ===== ColumnField with parentTable → real data fetcher =====

    @Test
    void columnField_signature() {
        var fields = List.<GraphitronField>of(columnField("title", "title", "TITLE"));
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", FILM_TABLE, fields);
        var m = method(spec, "title");
        assertThat(m.returnType().toString()).isEqualTo("java.lang.Object");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("env");
    }

    @Test
    void columnField_isNotStub() {
        var fields = List.<GraphitronField>of(columnField("title", "title", "TITLE"));
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", FILM_TABLE, fields);
        assertThat(method(spec, "title").code().toString()).doesNotContain("UnsupportedOperationException");
    }

    // ===== wiring() method =====

    @Test
    void wiring_signature() {
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", null, List.of());
        var w = method(spec, "wiring");
        assertThat(w.returnType().toString())
            .isEqualTo("graphql.schema.idl.TypeRuntimeWiring.Builder");
    }

    @Test
    void wiring_noFields_noDataFetchers() {
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", null, List.of());
        var w = method(spec, "wiring");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(");
    }

    @Test
    void wiring_registersAllFields() {
        var fields = List.<GraphitronField>of(
            columnField("title", "title", "TITLE"),
            columnField("releaseYear", "release_year", "RELEASE_YEAR"));
        var spec = TypeFieldsGenerator.generateTypeSpec("Film", FILM_TABLE, fields);
        var w = method(spec, "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"title\"");
        assertThat(w.code().toString()).contains("dataFetcher(\"releaseYear\"");
    }

    // ===== QueryTableField (root query → table) =====

    @Test
    void queryTableField_list_returnsResultRecord() {
        var field = queryTableField("films", true, List.of(), new OrderBySpec.None());
        var spec = TypeFieldsGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void queryTableField_single_returnsRecord() {
        var field = queryTableField("film", false, List.of(), new OrderBySpec.None());
        var spec = TypeFieldsGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "film").returnType().toString())
            .isEqualTo("org.jooq.Record");
    }

    // ===== @splitQuery TableField =====

    @Test
    void splitQuery_generatesAsyncFetcherAndRowsMethod() {
        var spec = TypeFieldsGenerator.generateTypeSpec("Language", null, List.of(splitQueryField("Language", "films")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .contains("films", "rowsFilms", "wiring");
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void splitQuery_asyncDataFetcherIsPublicStatic() {
        var m = method(specWithSplitQuery("Language", "films"), "films");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
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
        assertThat(m.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void splitQuery_rowsMethodTakesTypedKeyList() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.util.List<org.jooq.Row1<java.lang.Integer>>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("sources");
    }

    @Test
    void splitQuery_wiringRegistersDataFetcherByName() {
        var w = method(specWithSplitQuery("Language", "films"), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"films\"");
    }

    // ===== @service field with TableBoundReturnType =====

    @Test
    void serviceField_list_dataFetcherReturnsCompletableFutureListRecord() {
        var spec = specWithServiceField("Language", "films", true);
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_single_dataFetcherReturnsCompletableFutureRecord() {
        var spec = specWithServiceField("Language", "film", false);
        assertThat(method(spec, "film").returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<org.jooq.Record>");
    }

    @Test
    void serviceField_list_rowsMethodSignature() {
        var spec = specWithServiceField("Language", "films", true);
        var m = method(spec, "loadFilms");
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("keys", "dfe", "sel");
    }


    @Test
    void serviceField_wiringRegistersDataFetcherOnly() {
        var w = method(specWithServiceField("Language", "films", true), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"films\"");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(\"loadFilms\"");
    }
}
