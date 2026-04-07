package no.sikt.graphitron.rewrite.generators.splitquery;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SplitSourceCodeGeneratorTest {

    private static final SplitSourceCodeGenerator GEN = new SplitSourceCodeGenerator();

    private static String render(SplitSourceSpec spec) {
        var typeSpec = GEN.generate(spec);
        return JavaFile.builder("test.pkg", typeSpec).indent("    ").build().toString();
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
        assertThat(render(spec)).contains("rows(");
    }

    @Test
    void generate_methodIsPublicStatic() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("public static");
    }

    @Test
    void generate_noDslContextParameter() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).doesNotContain("DSLContext");
    }

    @Test
    void generate_parameterIsListOfRecord() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("List<Record> sources");
    }

    // ===== Return type =====

    @Test
    void generate_returnTypeIsRow2ForOneKeyField() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("Row2<Integer, Integer>");
    }

    @Test
    void generate_returnTypeIsRow3ForTwoKeyFields() {
        var spec = new SplitSourceSpec("Customer", "rentals", "CUSTOMER", List.of(
            new SplitSourceKeyFieldSpec("STORE_ID", "java.lang.Integer"),
            new SplitSourceKeyFieldSpec("CUSTOMER_ID", "java.lang.Integer")
        ));
        assertThat(render(spec)).contains("Row3<Integer, Integer, Integer>");
    }

    // ===== Method body =====

    @Test
    void generate_bodyUsesIntStreamOverSourcesSize() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec))
            .contains("IntStream.range(0,")
            .contains("sources.size()");
    }

    @Test
    void generate_rowCallIncludesTableColumn() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("LANGUAGE.LANGUAGE_ID");
    }

    @Test
    void generate_rowFirstArgIsOnePlusI() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("i + 1");
    }

    @Test
    void generate_rowExtractsColumnFromSourcesViaTypedGet() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("sources.get(i).get(LANGUAGE.LANGUAGE_ID)");
    }

    @Test
    void generate_rowExtractsAllKeyFields() {
        var spec = new SplitSourceSpec("Customer", "rentals", "CUSTOMER", List.of(
            new SplitSourceKeyFieldSpec("STORE_ID", "java.lang.Integer"),
            new SplitSourceKeyFieldSpec("CUSTOMER_ID", "java.lang.Integer")
        ));
        String out = render(spec);
        assertThat(out)
            .contains("sources.get(i).get(CUSTOMER.STORE_ID)")
            .contains("sources.get(i).get(CUSTOMER.CUSTOMER_ID)");
    }

    @Test
    void generate_bodyEndsWithToList() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains(".toList()");
    }

    @Test
    void generate_usesDslRow() {
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).contains("DSL.row(");
    }

    @Test
    void generate_noCastInRow() {
        // values are extracted via typed get(Field<T>) — no explicit cast should appear
        var spec = spec("Language", "films", "LANGUAGE",
            new SplitSourceKeyFieldSpec("LANGUAGE_ID", "java.lang.Integer"));
        assertThat(render(spec)).doesNotContain("(Integer)");
    }

    // ===== Helper =====

    private static SplitSourceSpec spec(String parentType, String fieldName, String tableField,
            SplitSourceKeyFieldSpec... fields) {
        return new SplitSourceSpec(parentType, fieldName, tableField, List.of(fields));
    }
}
