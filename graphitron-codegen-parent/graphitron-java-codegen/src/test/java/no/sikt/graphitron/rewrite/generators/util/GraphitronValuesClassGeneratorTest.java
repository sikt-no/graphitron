package no.sikt.graphitron.rewrite.generators.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphitronValuesClassGeneratorTest {

    @Test
    void generateAll_returnsExactlyOneClass() {
        var gen = new GraphitronValuesClassGenerator();
        assertThat(gen.generateAll()).hasSize(1);
    }

    @Test
    void generatedClass_isNamedGraphitronValues() {
        var gen = new GraphitronValuesClassGenerator();
        var spec = gen.generateAll().get(0);
        assertThat(spec.name()).isEqualTo("GraphitronValues");
    }

    @Test
    void generatedClass_containsGraphitronInputIdxField() {
        var gen = new GraphitronValuesClassGenerator();
        var output = gen.writeToString(gen.generateAll().get(0));
        assertThat(output)
            .contains("GRAPHITRON_INPUT_IDX")
            .contains("graphitron_input_idx")
            .contains("Field<Integer>")
            .contains("DSL.field(");
    }

    @Test
    void saveDirectory_isRewrite() {
        assertThat(new GraphitronValuesClassGenerator().getDefaultSaveDirectoryName())
            .isEqualTo("rewrite");
    }
}
