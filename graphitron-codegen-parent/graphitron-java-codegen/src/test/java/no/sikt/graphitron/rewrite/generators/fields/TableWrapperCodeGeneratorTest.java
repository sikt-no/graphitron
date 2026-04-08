package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableWrapperCodeGeneratorTest {

    private static final TableWrapperCodeGenerator GEN = new TableWrapperCodeGenerator();

    private static String render(String typeName) {
        return JavaFile.builder("test.pkg", GEN.generate(typeName)).indent("    ").build().toString();
    }

    // ===== Class structure =====

    @Test
    void generate_classNameHasTableWrapperSuffix() {
        assertThat(GEN.generate("Film").name()).isEqualTo("FilmTableWrapper");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(render("Film")).contains("public class FilmTableWrapper");
    }

    // ===== selectMany =====

    @Test
    void generate_selectManyMethodIsPresent() {
        assertThat(render("Film")).contains("selectMany(");
        assertThat(render("Customer")).contains("selectMany(");
    }

    @Test
    void generate_selectManyMethodIsPublicStatic() {
        assertThat(render("Film")).contains("public static");
    }

    @Test
    void generate_selectManyMethodReturnType() {
        assertThat(render("Film")).contains("Result<Record> selectMany(");
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

    // ===== selectOne =====

    @Test
    void generate_selectOneMethodIsPresent() {
        assertThat(render("Film")).contains("selectOne(");
        assertThat(render("Customer")).contains("selectOne(");
    }

    @Test
    void generate_selectOneMethodReturnType() {
        assertThat(render("Film")).contains("Record selectOne(");
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

    // ===== subselectMany =====

    @Test
    void generate_subselectManyMethodIsPresent() {
        assertThat(render("Film")).contains("subselectMany(");
        assertThat(render("Customer")).contains("subselectMany(");
    }

    @Test
    void generate_subselectManyMethodReturnType() {
        assertThat(render("Film")).contains("Field<Result<Record>> subselectMany(");
    }

    @Test
    void generate_subselectManyMethodParameters() {
        String out = render("Film");
        assertThat(out).contains("DataFetchingFieldSelectionSet sel");
        assertThat(out).contains("Condition condition");
        assertThat(out).contains("List<SortField<?>> orderBy");
    }

    @Test
    void generate_subselectManyMethodThrowsUnsupportedOperationException() {
        assertThat(render("Film")).contains("throw new UnsupportedOperationException()");
    }

    // ===== subselectOne =====

    @Test
    void generate_subselectOneMethodIsPresent() {
        assertThat(render("Film")).contains("subselectOne(");
        assertThat(render("Customer")).contains("subselectOne(");
    }

    @Test
    void generate_subselectOneMethodReturnType() {
        assertThat(render("Film")).contains("Field<Record> subselectOne(");
    }

    @Test
    void generate_subselectOneMethodParameters() {
        String out = render("Film");
        assertThat(out).contains("DataFetchingFieldSelectionSet sel");
        assertThat(out).contains("Condition condition");
    }

    @Test
    void generate_subselectOneMethodThrowsUnsupportedOperationException() {
        assertThat(render("Film")).contains("throw new UnsupportedOperationException()");
    }

    // ===== All four methods present =====

    @Test
    void generate_allFourMethodsArePresent() {
        String out = render("Film");
        assertThat(out).contains("selectMany(");
        assertThat(out).contains("selectOne(");
        assertThat(out).contains("subselectMany(");
        assertThat(out).contains("subselectOne(");
    }
}
