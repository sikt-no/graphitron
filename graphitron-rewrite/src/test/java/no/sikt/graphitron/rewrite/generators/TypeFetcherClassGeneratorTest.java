package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
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

    // ===== ColumnField with parentTable → LightDataFetcher =====

    @Test
    void columnFetcher_hasThreeParameters() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").parameters())
            .extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment", "java.lang.Object", "java.lang.Object");
    }

    @Test
    void columnFetcher_parameterNamesAreEnvLocalContextSource() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").parameters())
            .extracting(p -> p.name())
            .containsExactly("env", "localContext", "source");
    }

    @Test
    void columnFetcher_returnsColumnType() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").returnType().toString()).isEqualTo("java.lang.String");
    }

    @Test
    void columnFetcher_integerColumnReturnsInteger() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("filmId", "film_id", "FILM_ID", "java.lang.Integer")));
        assertThat(method(spec, "filmId").returnType().toString()).isEqualTo("java.lang.Integer");
    }

    @Test
    void columnFetcher_isNotStub() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").code().toString()).doesNotContain("UnsupportedOperationException");
    }

    @Test
    void columnFetcher_usesSourceNotEnvGetSource() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        var code = method(spec, "title").code().toString();
        assertThat(code).contains("source");
        assertThat(code).doesNotContain("env.getSource()");
    }

    @Test
    void columnFetcher_isPublicStatic() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "title").modifiers())
            .containsExactlyInAnyOrder(javax.lang.model.element.Modifier.PUBLIC,
                javax.lang.model.element.Modifier.STATIC);
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
    void wiring_columnField_castsToLightDataFetcherWithPreciseType() {
        var spec = TypeFetcherClassGenerator.generateTypeSpec("Film", FILM_TABLE,
            List.of(columnField("title", "title", "TITLE", "java.lang.String")));
        assertThat(method(spec, "wiring").code().toString())
            .contains("(graphql.schema.LightDataFetcher<java.lang.String>) FilmFetchers::title");
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
}
