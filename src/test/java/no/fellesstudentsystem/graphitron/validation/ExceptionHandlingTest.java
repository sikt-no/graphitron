package no.fellesstudentsystem.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Exception handling validation - Checks run when building the schema for exception handling")
public class ExceptionHandlingTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "exception";
    }

    @Test
    @DisplayName("Class name is not set for handler")
    void missingClassName() {
        assertThatThrownBy(() -> getProcessedSchema("missingClassName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'className´ directive argument must be defined for error handler of type GENERIC");
    }
}
