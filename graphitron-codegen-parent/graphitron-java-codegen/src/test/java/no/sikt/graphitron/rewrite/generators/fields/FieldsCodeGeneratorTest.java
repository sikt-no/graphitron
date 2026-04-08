package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldsCodeGeneratorTest {

    private static final FieldsCodeGenerator GEN = new FieldsCodeGenerator();

    private static String render(String typeName) {
        return JavaFile.builder("test.pkg", GEN.generate(typeName)).indent("    ").build().toString();
    }

    @Test
    void generate_classNameHasFieldsSuffix() {
        assertThat(GEN.generate("Film").name()).isEqualTo("FilmFields");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(render("Film")).contains("public class FilmFields");
    }
}
