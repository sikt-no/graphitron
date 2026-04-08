package no.sikt.graphitron.rewrite.generators.splitquery;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SplitSourceCodeGeneratorTest {

    private static final ClassName TABLES = ClassName.get("test.jooq", "Tables");
    private static final SplitSourceCodeGenerator GEN = new SplitSourceCodeGenerator(TABLES);

    private static MethodSpec method(SplitSourceSpec spec) {
        return GEN.generate(spec).methodSpecs().get(0);
    }

    // ===== Class naming =====

    @Test
    void generate_classNameCombinesParentTypeFieldNameAndDerivedSource() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(GEN.generate(spec).name()).isEqualTo("LanguageFilmsDerivedSource");
    }

    @Test
    void generate_capitalizesFieldName() {
        var spec = spec("Film", "actors", "FILM",
            new SplitSourceKeyFieldSpec("FILM_ID", "java.lang.Integer"));
        assertThat(GEN.generate(spec).name()).isEqualTo("FilmActorsDerivedSource");
    }

    // ===== Method signature =====

    @Test
    void generate_containsRowsMethod() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).name()).isEqualTo("rows");
    }

    @Test
    void generate_methodIsPublicStatic() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).modifiers())
            .containsExactlyInAnyOrder(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC);
    }

    @Test
    void generate_noDslContextParameter() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).parameters())
            .extracting(p -> p.type().toString())
            .doesNotContain("org.jooq.DSLContext");
    }

    @Test
    void generate_parameterIsListOfRecord() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).parameters().get(0).type().toString())
            .isEqualTo("java.util.List<org.jooq.Record>");
    }

    // ===== Return type =====

    @Test
    void generate_returnTypeIsRow2ForOneKeyField() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Row2<java.lang.Integer, java.lang.Integer>>");
    }

    @Test
    void generate_returnTypeIsRow3ForTwoKeyFields() {
        var spec = new SplitSourceSpec("Customer", "rentals", "CUSTOMER", List.of(
            new SplitSourceKeyFieldSpec("STORE_ID", "java.lang.Integer"),
            new SplitSourceKeyFieldSpec("CUSTOMER_ID", "java.lang.Integer")
        ));
        assertThat(method(spec).returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Row3<java.lang.Integer, java.lang.Integer, java.lang.Integer>>");
    }

    // ===== Method body =====

    @Test
    void generate_bodyUsesIntStreamOverSourcesSize() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        String code = method(spec).code().toString();
        assertThat(code)
            .contains("IntStream.range(0,")
            .contains("sources.size()");
    }

    @Test
    void generate_rowCallIncludesTableColumn() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).code().toString()).contains("LANGUAGE.LANGUAGE_ID");
    }

    @Test
    void generate_rowFirstArgIsOnePlusI() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).code().toString()).contains("i + 1");
    }

    @Test
    void generate_rowExtractsColumnFromSourcesViaTypedGet() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        String code = method(spec).code().toString();
        assertThat(code)
            .contains("sources.get(i).get(")
            .contains("LANGUAGE.LANGUAGE_ID");
    }

    @Test
    void generate_rowExtractsAllKeyFields() {
        var spec = new SplitSourceSpec("Customer", "rentals", "CUSTOMER", List.of(
            new SplitSourceKeyFieldSpec("STORE_ID", "java.lang.Integer"),
            new SplitSourceKeyFieldSpec("CUSTOMER_ID", "java.lang.Integer")
        ));
        String code = method(spec).code().toString();
        assertThat(code)
            .contains("CUSTOMER.STORE_ID")
            .contains("CUSTOMER.CUSTOMER_ID");
    }

    @Test
    void generate_bodyEndsWithToList() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).code().toString()).contains(".toList()");
    }

    @Test
    void generate_usesDslRow() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).code().toString()).contains("DSL.row(");
    }

    @Test
    void generate_noCastInRow() {
        // values are extracted via typed get(Field<T>) — no explicit cast should appear
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(method(spec).code().toString()).doesNotContain("(Integer)");
    }

    // ===== Helper =====

    private static SplitSourceSpec spec(String parentType, String fieldName, String tableField,
            SplitSourceKeyFieldSpec... fields) {
        return new SplitSourceSpec(parentType, fieldName, tableField, List.of(fields));
    }
}
