package no.fellesstudentsystem.graphitron.common;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

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
