package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableCodeGeneratorTest {

    private static final TableCodeGenerator GEN = new TableCodeGenerator();

    private static String render(String tableName) {
        return JavaFile.builder("test.pkg", GEN.generate(tableName)).indent("    ").build().toString();
    }

    // ===== Class naming =====

    @Test
    void generate_classNameMatchesTableName() {
        assertThat(GEN.generate("Film").name()).isEqualTo("Film");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(render("Film")).contains("public class Film");
    }

    // ===== toPascalCase — converts jOOQ UPPER_SNAKE field names to class names =====

    @Test
    void toPascalCase_singleWordFieldName() {
        assertThat(TableCodeGenerator.toPascalCase("FILM")).isEqualTo("Film");
    }

    @Test
    void toPascalCase_multiWordFieldName() {
        assertThat(TableCodeGenerator.toPascalCase("FILM_ACTOR")).isEqualTo("FilmActor");
    }

    // ===== selectMany =====

    @Test
    void generate_selectManyMethodIsPresent() {
        assertThat(render("Film")).contains("selectMany(");
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

    // ===== subselectMany =====

    @Test
    void generate_subselectManyMethodIsPresent() {
        assertThat(render("Film")).contains("subselectMany(");
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

    // ===== subselectOne =====

    @Test
    void generate_subselectOneMethodIsPresent() {
        assertThat(render("Film")).contains("subselectOne(");
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
