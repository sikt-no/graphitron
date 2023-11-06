package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.Extension;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphitron.validation.ProcessedDefinitionsValidator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Disabled
public class GraphQLGeneratorExtendedFunctionalityTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "query";
    private static final String EXCEPTION_MSG = "I've been expecting you";

    public GraphQLGeneratorExtendedFunctionalityTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema)
        );
        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                List.of(),
                List.of(),
                List.of(new Extension(ProcessedDefinitionsValidator.class.getName(), ExceptionThrowingValidator.class.getName()))
        );
    }

    @Test
    void generate_shouldUseExtendedValidator() throws IOException {
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
