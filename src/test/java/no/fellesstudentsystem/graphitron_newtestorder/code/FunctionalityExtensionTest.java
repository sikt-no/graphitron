package no.fellesstudentsystem.graphitron_newtestorder.code;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.fellesstudentsystem.graphitron.configuration.Extension;
import no.fellesstudentsystem.graphitron.validation.ProcessedDefinitionsValidator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.setProperties;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FunctionalityExtensionTest extends GeneratorTest {
    private static final String EXCEPTION_MSG = "I've been expecting you";

    @Test
    @DisplayName("Throw exception from extended validator")
    void useExtendedValidator() {
        setProperties(List.of(), List.of(), List.of(new Extension(ProcessedDefinitionsValidator.class.getName(), ExceptionThrowingValidator.class.getName())), "");
        assertThatThrownBy(() -> new ProcessedSchema(new TypeDefinitionRegistry()).validate(false))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(EXCEPTION_MSG);
    }

    public static class ExceptionThrowingValidator extends ProcessedDefinitionsValidator {
        public ExceptionThrowingValidator(ProcessedSchema schema) {
            super(schema);
        }

        @Override
        public void validateThatProcessedDefinitionsConformToJOOQNaming() {
            throw new UnsupportedOperationException(EXCEPTION_MSG);
        }
    }
}
