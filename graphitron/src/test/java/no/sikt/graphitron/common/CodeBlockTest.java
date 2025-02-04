package no.sikt.graphitron.common;

import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphql.schema.ProcessedSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functionality that is used for testing individual code blocks.
 */
public abstract class CodeBlockTest extends GeneratorTest {
    protected abstract CodeBlock makeCodeBlock(ProcessedSchema schema);

    protected void compareCodeBlockResult(String path, String expected, SchemaComponent... components) {
        assertThat(makeCodeBlock(getProcessedSchema(path, components)).toString()).isEqualToIgnoringWhitespace(expected);
    }
}
