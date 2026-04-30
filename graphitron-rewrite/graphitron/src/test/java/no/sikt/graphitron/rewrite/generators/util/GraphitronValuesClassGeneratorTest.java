package no.sikt.graphitron.rewrite.generators.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class GraphitronValuesClassGeneratorTest {

    @Test
    void generate_returnsExactlyOneClass() {
        assertThat(GraphitronValuesClassGenerator.generate()).hasSize(1);
    }

    @Test
    void generatedClass_isNamedGraphitronValues() {
        assertThat(GraphitronValuesClassGenerator.generate().get(0).name()).isEqualTo("GraphitronValues");
    }

    @Test
    void generatedClass_hasGraphitronInputIdxField() {
        var field = GraphitronValuesClassGenerator.generate().get(0).fieldSpecs().get(0);
        assertThat(field.name()).isEqualTo("GRAPHITRON_INPUT_IDX");
        assertThat(field.type().toString()).isEqualTo("org.jooq.Field<java.lang.Integer>");
        assertThat(field.initializer().toString()).contains("graphitron_input_idx");
    }
}
