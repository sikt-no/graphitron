package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.idl.errors.SchemaProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ValidationEnabledTest extends AbstractTest {
    @Test
    @DisplayName("Checks that our code runs the graphql schema validation")
    void checkValidation() {
        assertThatThrownBy(() -> makeSchema("invalidSchema"))
                .isInstanceOf(SchemaProblem.class)
                .hasMessage("errors=['q' [@4:5] failed to provide a value for the non null argument 'p' on directive 'D']");
    }
}
