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

    // ===== filmSelect =====

    @Test
    void generate_selectMethodNameUsesDecapitalizedTypeName() {
        assertThat(render("Film")).contains("filmSelect(");
        assertThat(render("Customer")).contains("customerSelect(");
    }

    @Test
    void generate_selectMethodIsPublicStatic() {
        assertThat(render("Film")).contains("public static");
    }

    @Test
    void generate_selectMethodReturnType() {
        assertThat(render("Film")).contains("SelectFinalStep<Record>");
    }

    @Test
    void generate_selectMethodParameters() {
        String out = render("Film");
        assertThat(out).contains("DSLContext ctx");
        assertThat(out).contains("DataFetchingFieldSelectionSet sel");
        assertThat(out).contains("Condition condition");
        assertThat(out).contains("List<SortField<?>> orderBy");
    }

    @Test
    void generate_selectMethodThrowsUnsupportedOperationException() {
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

    // ===== Separate methods =====

    @Test
    void generate_selectAndNestedAreDistinctMethods() {
        String out = render("Film");
        assertThat(out).contains("filmSelect(");
        assertThat(out).contains("filmNested(");
    }
}
