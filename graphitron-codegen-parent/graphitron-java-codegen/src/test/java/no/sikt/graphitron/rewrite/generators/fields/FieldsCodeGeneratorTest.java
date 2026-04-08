package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldsCodeGeneratorTest {

    private static final FieldsCodeGenerator GEN = new FieldsCodeGenerator();

    private static String render(String typeName) {
        return JavaFile.builder("test.pkg", GEN.generate(typeName)).indent("    ").build().toString();
    }

    // ===== Class structure =====

    @Test
    void generate_classNameHasFieldsSuffix() {
        assertThat(GEN.generate("Film").name()).isEqualTo("FilmFields");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(render("Film")).contains("public class FilmFields");
    }

    // ===== filmSelectMany =====

    @Test
    void generate_selectManyMethodNameUsesDecapitalizedTypeName() {
        assertThat(render("Film")).contains("filmSelectMany(");
        assertThat(render("Customer")).contains("customerSelectMany(");
    }

    @Test
    void generate_selectManyMethodIsPublicStatic() {
        assertThat(render("Film")).contains("public static");
    }

    @Test
    void generate_selectManyMethodReturnType() {
        assertThat(render("Film")).contains("Result<Record>");
    }

    @Test
    void generate_selectManyMethodParameters() {
        String out = render("Film");
        assertThat(out).contains("DataFetchingEnvironment env");
        assertThat(out).contains("Condition condition");
        assertThat(out).contains("List<SortField<?>> orderBy");
    }

    @Test
    void generate_selectManyMethodThrowsUnsupportedOperationException() {
        assertThat(render("Film")).contains("throw new UnsupportedOperationException()");
    }

    // ===== filmSelectOne =====

    @Test
    void generate_selectOneMethodNameUsesDecapitalizedTypeName() {
        assertThat(render("Film")).contains("filmSelectOne(");
        assertThat(render("Customer")).contains("customerSelectOne(");
    }

    @Test
    void generate_selectOneMethodReturnType() {
        assertThat(render("Film")).contains("Record filmSelectOne(");
    }

    @Test
    void generate_selectOneMethodParameters() {
        String out = render("Film");
        assertThat(out).contains("DataFetchingEnvironment env");
        assertThat(out).contains("Condition condition");
    }

    @Test
    void generate_selectOneMethodThrowsUnsupportedOperationException() {
        assertThat(render("Film")).contains("throw new UnsupportedOperationException()");
    }

    // ===== filmNested =====

    @Test
    void generate_nestedMethodNameUsesDecapitalizedTypeName() {
        assertThat(render("Film")).contains("filmNested(");
        assertThat(render("Customer")).contains("customerNested(");
    }

    @Test
    void generate_nestedMethodReturnType() {
        assertThat(render("Film")).contains("Field<Result<Record>>");
    }

    @Test
    void generate_nestedMethodParameters() {
        String out = render("Film");
        assertThat(out).contains("DataFetchingFieldSelectionSet sel");
        assertThat(out).contains("Condition condition");
        assertThat(out).contains("List<SortField<?>> orderBy");
    }

    @Test
    void generate_nestedMethodThrowsUnsupportedOperationException() {
        assertThat(render("Film")).contains("throw new UnsupportedOperationException()");
    }

    // ===== Distinct methods =====

    @Test
    void generate_allThreeMethodsArePresent() {
        String out = render("Film");
        assertThat(out).contains("filmSelectMany(");
        assertThat(out).contains("filmSelectOne(");
        assertThat(out).contains("filmNested(");
    }
}
