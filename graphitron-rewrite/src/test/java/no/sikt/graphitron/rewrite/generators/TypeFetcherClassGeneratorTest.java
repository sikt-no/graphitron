package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
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
 * Unit tests for {@link TypeFetcherClassGenerator}. Tests verify structural properties of the
 * generated TypeSpec (method names, return types, parameter signatures) — not the generated code
 * body. Code correctness is verified by compiling and executing the generated output in the
 * {@code graphitron-rewrite-test-spec} module.
 */
class TypeFetcherClassGeneratorTest {

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
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(spec.name()).isEqualTo("FilmFetchers");
    }

    @Test
    void generate_alwaysHasWiringMethod() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name).contains("wiring");
    }

    // ===== Stub methods (column field without parentTable falls to stub) =====

    @Test
    void stub_hasEnvParameter() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void stub_returnsObject() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").returnType().toString())
            .isEqualTo("java.lang.Object");
    }

    @Test
    void stub_bodyThrowsUnsupportedOperationException() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").code().toString()).contains("UnsupportedOperationException");
    }

    // ===== ColumnField with parentTable → wired via ColumnFetcher, no per-field method =====

    @Test
    void columnFetcher_withParentTable_noPerFieldMethod() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .doesNotContain("title");
    }

    @Test
    void columnFetcher_withParentTable_onlyWiringMethod() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(spec.methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("wiring");
    }

    // ===== QueryTableField =====

    @Test
    void queryTableField_list_returnsResultRecord() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void queryTableField_single_returnsRecord() {
        var field = queryTableField("film", false);
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "film").returnType().toString())
            .isEqualTo("org.jooq.Record");
    }

    @Test
    void queryTableField_hasEnvParameter() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void queryTableField_isNotStub() {
        var field = queryTableField("films", true);
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "films").code().toString()).doesNotContain("UnsupportedOperationException");
    }

    // ===== wiring() method =====

    @Test
    void wiring_signature() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(method(spec, "wiring").returnType().toString())
            .isEqualTo("graphql.schema.idl.TypeRuntimeWiring.Builder");
    }

    @Test
    void wiring_noFields_noDataFetchers() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", null, List.of());
        assertThat(method(spec, "wiring").code().toString()).doesNotContain("dataFetcher(");
    }

    @Test
    void wiring_columnField_usesColumnFetcher() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        var wiringCode = method(spec, "wiring").code().toString();
        assertThat(wiringCode).contains("ColumnFetcher");
        assertThat(wiringCode).contains("Tables.FILM.TITLE");
    }

    @Test
    void wiring_queryField_usesPlainMethodReference() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null,
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
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE, fields);
        var wiringCode = method(spec, "wiring").code().toString();
        assertThat(wiringCode).contains("dataFetcher(\"title\"");
        assertThat(wiringCode).contains("dataFetcher(\"releaseYear\"");
    }

    // ===== QueryLookupTableField =====

    private static GraphitronField lookupQueryField(String name, List<BodyParam> bodyParams) {
        var wrapper = new FieldWrapper.List(false, false);
        var returnType = new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
        var callParams = bodyParams.stream()
            .map(bp -> new CallParam(bp.name(), bp.extraction()))
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
            new CallSiteExtraction.Direct(), "Int");
    }

    private static BodyParam scalarKeyParam(String name, String javaName, String javaType) {
        return new BodyParam(name, new ColumnRef(name, javaName, javaType), javaType, false, false,
            new CallSiteExtraction.Direct(), "Int");
    }

    @Test
    void queryLookupField_returnsResultRecord() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void queryLookupField_hasEnvParameter() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void queryLookupField_isNotStub() {
        var field = lookupQueryField("filmById", List.of(listKeyParam("film_id", "FILM_ID", "java.lang.Integer")));
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "filmById").code().toString())
            .doesNotContain("UnsupportedOperationException");
    }

    @Test
    void queryLookupField_scalarKey_isNotStub() {
        var fields = List.of(
            listKeyParam("customer_id", "CUSTOMER_ID", "java.lang.Integer"),
            scalarKeyParam("store_id", "STORE_ID", "java.lang.Integer"));
        var field = lookupQueryField("customerById", fields);
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Query", null, List.of(field));
        assertThat(method(spec, "customerById").code().toString())
            .doesNotContain("UnsupportedOperationException");
    }
}
