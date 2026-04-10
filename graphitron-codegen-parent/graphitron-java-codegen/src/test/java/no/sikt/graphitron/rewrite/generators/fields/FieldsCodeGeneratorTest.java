package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.field.ArgumentRef;
import no.sikt.graphitron.rewrite.field.ChildField;
import no.sikt.graphitron.rewrite.field.ColumnRef;
import no.sikt.graphitron.rewrite.field.ExternalRef;
import no.sikt.graphitron.rewrite.field.FieldConditionRef;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.field.ServiceMethodRef;
import no.sikt.graphitron.rewrite.field.SourcesRef;
import no.sikt.graphitron.rewrite.type.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

class FieldsCodeGeneratorTest {

    private static final FieldsCodeGenerator GEN = new FieldsCodeGenerator();

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

    private static GraphitronField field(String name) {
        return new ChildField.ColumnField("Film", name, null, name,
            new ColumnRef("COL", "", "java.lang.String"), false);
    }

    private static GraphitronField splitQueryField(String parentType, String name) {
        return new ChildField.SplitTableField(parentType, name, null,
            new ReturnTypeRef.TableBoundReturnType("Film",
                new TableRef("film", "FILM", "Film", true, List.of(), List.of()),
                new FieldWrapper.List(false, false, null, List.of())),
            List.of(), new FieldConditionRef.NoFieldCondition(), List.of());
    }

    private static GraphitronField serviceField(String parentType, String name, boolean isList) {
        var returnWrapper = isList
            ? (FieldWrapper) new FieldWrapper.List(true, true, null, List.of())
            : new FieldWrapper.Single(true);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film",
            new TableRef("film", "FILM", "Film", true, List.of(), List.of()),
            returnWrapper);
        var smr = new ServiceMethodRef(
            List.of(
                new ServiceMethodRef.ServiceParam.SourcesParam("keys", new SourcesRef.RowKeyed(List.of("java.lang.Integer"))),
                new ServiceMethodRef.ServiceParam.ArgParam("filter", "java.lang.String"),
                new ServiceMethodRef.ServiceParam.ContextParam("tenantId", "java.lang.String")
            ),
            "java.util.List"
        );
        return new ChildField.ServiceTableField(
            parentType, name, null, returnType,
            List.of(), new ExternalRef("no.example.FilmService", "getFilms"),
            List.of(new ArgumentRef.ScalarArg.ParamArg("filter", "String", false, false)),
            List.of("tenantId"), smr);
    }

    private static TypeSpec spec(String typeName, List<String> fieldNames) {
        return GEN.generate(typeName, null, fieldNames.stream().map(FieldsCodeGeneratorTest::field).toList());
    }

    private static TypeSpec specWithSplitQuery(String typeName, String fieldName) {
        return GEN.generate(typeName, null, List.of(splitQueryField(typeName, fieldName)));
    }

    private static TypeSpec specWithServiceField(String parentType, String fieldName, boolean isList) {
        var parentTable = new TableRef("language", "LANGUAGE", "Language", true, List.of("language_id"), List.of("java.lang.Integer"));
        return GEN.generate(parentType, parentTable, List.of(serviceField(parentType, fieldName, isList)));
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
        assertThat(spec("Film", List.of()).name()).isEqualTo("FilmFields");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(spec("Film", List.of()).modifiers()).contains(Modifier.PUBLIC);
    }

    // ===== Per-field stub methods =====

    @Test
    void generate_fieldMethodIsPresent() {
        assertThat(spec("Film", List.of("title")).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("title");
    }

    @Test
    void generate_fieldMethodIsPublicStatic() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void generate_fieldMethodReturnsObject() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.returnType().toString()).isEqualTo("java.lang.Object");
    }

    @Test
    void generate_fieldMethodTakesDataFetchingEnvironment() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env");
    }

    @Test
    void generate_fieldMethodThrowsUnsupportedOperationException() {
        var m = method(spec("Film", List.of("title")), "title");
        assertThat(m.code().toString()).contains("UnsupportedOperationException()");
    }

    @Test
    void generate_multipleFields_allPresent() {
        var s = spec("Film", List.of("title", "releaseYear"));
        assertThat(s.methodSpecs()).extracting(MethodSpec::name).contains("title", "releaseYear");
    }

    // ===== wiring() method =====

    @Test
    void generate_wiringMethodIsPresent() {
        assertThat(spec("Film", List.of()).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("wiring");
    }

    @Test
    void generate_wiringMethodIsPublicStatic() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void generate_wiringMethodReturnsTypeRuntimeWiringBuilder() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.returnType().toString())
            .isEqualTo("graphql.schema.idl.TypeRuntimeWiring.Builder");
    }

    @Test
    void generate_wiringMethod_containsTypeName() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.code().toString()).contains("newTypeWiring(\"Film\")");
    }

    @Test
    void generate_wiringMethod_usesMethodReference() {
        var w = method(spec("Film", List.of("title")), "wiring");
        assertThat(w.code().toString()).contains("FilmFields::title");
    }

    @Test
    void generate_wiringMethod_registersFieldByName() {
        var w = method(spec("Film", List.of("title")), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"title\"");
    }

    @Test
    void generate_wiringMethod_noFields_noDataFetchers() {
        var w = method(spec("Film", List.of()), "wiring");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(");
    }

    @Test
    void generate_wiringMethod_multipleFields_allRegistered() {
        var w = method(spec("Film", List.of("title", "releaseYear")), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"title\"");
        assertThat(w.code().toString()).contains("dataFetcher(\"releaseYear\"");
    }

    // ===== @splitQuery TableField =====

    @Test
    void splitQuery_asyncDataFetcherIsPresent() {
        assertThat(specWithSplitQuery("Language", "films").methodSpecs())
            .extracting(MethodSpec::name)
            .contains("films");
    }

    @Test
    void splitQuery_asyncDataFetcherReturnsCompletableFuture() {
        var m = method(specWithSplitQuery("Language", "films"), "films");
        assertThat(m.returnType().toString())
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
    void splitQuery_asyncDataFetcherThrowsUnsupportedOperationException() {
        var m = method(specWithSplitQuery("Language", "films"), "films");
        assertThat(m.code().toString()).contains("UnsupportedOperationException()");
    }

    @Test
    void splitQuery_rowsMethodIsPresent() {
        assertThat(specWithSplitQuery("Language", "films").methodSpecs())
            .extracting(MethodSpec::name)
            .contains("rowsFilms");
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
    void splitQuery_rowsMethodTakesListOfRecord() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.util.List<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("sources");
    }

    @Test
    void splitQuery_rowsMethodThrowsUnsupportedOperationException() {
        var m = method(specWithSplitQuery("Language", "films"), "rowsFilms");
        assertThat(m.code().toString()).contains("UnsupportedOperationException()");
    }

    @Test
    void splitQuery_wiringRegistersDataFetcherByName() {
        var w = method(specWithSplitQuery("Language", "films"), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"films\"");
    }

    // ===== @service field with TableBoundReturnType — data fetcher =====

    @Test
    void serviceField_list_dataFetcherIsPresent() {
        assertThat(specWithServiceField("Language", "films", true).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("films");
    }

    @Test
    void serviceField_list_returnsCompletableFutureListRecord() {
        var m = method(specWithServiceField("Language", "films", true), "films");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
    }

    @Test
    void serviceField_list_codeContainsDataLoaderComputeIfAbsent() {
        var m = method(specWithServiceField("Language", "films", true), "films");
        assertThat(m.code().toString()).contains("computeIfAbsent");
        assertThat(m.code().toString()).contains("newDataLoaderWithContext");
    }

    @Test
    void serviceField_list_codeContainsSelectMany() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.code().toString()).contains("selectMany");
    }

    @Test
    void serviceField_list_codeContainsPKRowKey() {
        var m = method(specWithServiceField("Language", "films", true), "films");
        assertThat(m.code().toString()).contains("LANGUAGE_ID");
    }

    @Test
    void serviceField_list_wiringRegistersDataFetcherOnly() {
        var w = method(specWithServiceField("Language", "films", true), "wiring");
        assertThat(w.code().toString()).contains("dataFetcher(\"films\"");
        assertThat(w.code().toString()).doesNotContain("dataFetcher(\"loadFilms\"");
    }

    @Test
    void serviceField_single_returnsCompletableFutureRecord() {
        var m = method(specWithServiceField("Language", "film", false), "film");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<org.jooq.Record>");
    }

    @Test
    void serviceField_single_codeContainsSelectOne() {
        var m = method(specWithServiceField("Language", "film", false), "loadFilm");
        assertThat(m.code().toString()).contains("selectOne");
    }

    // ===== @service field — rows method =====

    @Test
    void serviceField_list_rowsMethodIsPresent() {
        assertThat(specWithServiceField("Language", "films", true).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("loadFilms");
    }

    @Test
    void serviceField_list_rowsMethodIsPublicStatic() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.modifiers()).containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void serviceField_list_rowsMethodParamsAreKeysEnvSel() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<org.jooq.Row1<java.lang.Integer>>",
                "graphql.schema.DataFetchingEnvironment",
                "graphql.schema.SelectedField");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("keys", "dfe", "sel");
    }

    @Test
    void serviceField_list_rowsMethodExtractsArgsFromDfe() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.code().toString()).contains("getArgument(\"filter\")");
    }

    @Test
    void serviceField_list_rowsMethodExtractsContextFromGraphitronContext() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.code().toString()).contains("getContextArgument");
        assertThat(m.code().toString()).contains("tenantId");
    }

    @Test
    void serviceField_list_rowsMethodCallsService() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.code().toString()).contains("getFilms");
    }

    @Test
    void serviceField_list_rowsMethodReturnsSelectMany() {
        var m = method(specWithServiceField("Language", "films", true), "loadFilms");
        assertThat(m.code().toString()).contains("selectMany");
    }

    @Test
    void serviceField_single_rowsMethodReturnsSelectOne() {
        var m = method(specWithServiceField("Language", "film", false), "loadFilm");
        assertThat(m.code().toString()).contains("selectOne");
    }
}
