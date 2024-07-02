package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.Extension;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.validation.ProcessedDefinitionsValidator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Disabled
public class GraphQLGeneratorExtendedFunctionalityTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "query";
    private static final String EXCEPTION_MSG = "I've been expecting you";

    public GraphQLGeneratorExtendedFunctionalityTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                List.of(),
                List.of(),
                List.of(new Extension(ProcessedDefinitionsValidator.class.getName(), ExceptionThrowingValidator.class.getName()))
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new UpdateResolverClassGenerator(schema),
                new UpdateDBClassGenerator(schema)
        );
    }

    @Test
    void generate_shouldUseExtendedValidator() {
        assertThatThrownBy(() -> getProcessedSchema("queryWithPagination"))
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
