package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldsCodeGeneratorTest {

    private static final FieldsCodeGenerator GEN = new FieldsCodeGenerator();

    private static String render(String typeName, List<String> fieldNames) {
        return JavaFile.builder("test.pkg", GEN.generate(typeName, fieldNames))
            .indent("    ")
            .build()
            .toString();
    }

    // ===== Class naming =====

    @Test
    void generate_classNameIsTypeNamePlusFields() {
        assertThat(GEN.generate("Film", List.of()).name()).isEqualTo("FilmFields");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(render("Film", List.of())).contains("public class FilmFields");
    }

    // ===== Per-field stub methods =====

    @Test
    void generate_fieldMethodIsPresent() {
        assertThat(render("Film", List.of("title"))).contains("title(");
    }

    @Test
    void generate_fieldMethodIsPublicStatic() {
        assertThat(render("Film", List.of("title"))).contains("public static Object title(");
    }

    @Test
    void generate_fieldMethodTakesDataFetchingEnvironment() {
        assertThat(render("Film", List.of("title"))).contains("DataFetchingEnvironment env");
    }

    @Test
    void generate_fieldMethodThrowsUnsupportedOperationException() {
        assertThat(render("Film", List.of("title"))).contains("throw new UnsupportedOperationException()");
    }

    @Test
    void generate_multipleFields_allPresent() {
        String out = render("Film", List.of("title", "releaseYear"));
        assertThat(out).contains("title(");
        assertThat(out).contains("releaseYear(");
    }

    // ===== wiring() method =====

    @Test
    void generate_wiringMethodIsPresent() {
        assertThat(render("Film", List.of())).contains("wiring()");
    }

    @Test
    void generate_wiringMethodIsPublicStatic() {
        assertThat(render("Film", List.of())).contains("public static");
        assertThat(render("Film", List.of())).contains("wiring()");
    }

    @Test
    void generate_wiringMethodReturnsBuilderType() {
        assertThat(render("Film", List.of())).contains("TypeRuntimeWiring.Builder wiring()");
    }

    @Test
    void generate_wiringMethodContainsTypeName() {
        assertThat(render("Film", List.of())).contains("newTypeWiring(\"Film\")");
    }

    @Test
    void generate_wiringMethodUsesMethodReference() {
        assertThat(render("Film", List.of("title"))).contains("FilmFields::title");
    }

    @Test
    void generate_wiringMethodRegistersFieldByName() {
        assertThat(render("Film", List.of("title"))).contains("dataFetcher(\"title\"");
    }

    @Test
    void generate_wiringMethod_noFields_noDataFetchers() {
        assertThat(render("Film", List.of())).doesNotContain("dataFetcher(");
    }

    @Test
    void generate_wiringMethod_multipleFields_allRegistered() {
        String out = render("Film", List.of("title", "releaseYear"));
        assertThat(out).contains("dataFetcher(\"title\"");
        assertThat(out).contains("dataFetcher(\"releaseYear\"");
    }
}
