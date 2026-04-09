package no.sikt.graphitron.rewrite.generators.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphitronValuesClassGeneratorTest {

    private static final GraphitronValuesClassGenerator GEN = new GraphitronValuesClassGenerator();

    @Test
    void generateAll_returnsExactlyOneClass() {
        assertThat(GEN.generateAll(null)).hasSize(1);
    }

    @Test
    void generatedClass_isNamedGraphitronValues() {
        assertThat(GEN.generateAll(null).get(0).name()).isEqualTo("GraphitronValues");
    }

    @Test
    void generatedClass_hasGraphitronInputIdxField() {
        var field = GEN.generateAll(null).get(0).fieldSpecs().get(0);
        assertThat(field.name()).isEqualTo("GRAPHITRON_INPUT_IDX");
        assertThat(field.type().toString()).isEqualTo("org.jooq.Field<java.lang.Integer>");
        assertThat(field.initializer().toString()).contains("graphitron_input_idx");
    }

    @Test
    void saveDirectory_isRewrite() {
        assertThat(GEN.getDefaultSaveDirectoryName()).isEqualTo("rewrite");
    }
}
