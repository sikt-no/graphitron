package no.sikt.graphitron.code;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.common.CodeBlockTest;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.code.InputEnumConversionFunctionTest.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM_CONVERTED;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.toJOOQEnumConverter;

@DisplayName("Query output enums - Enum conversion for queries")
public class OutputEnumConversionFunctionTest extends CodeBlockTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/enums/output";
    }

    @Override
    protected CodeBlock makeCodeBlock(ProcessedSchema schema) {
        var field = schema
                .getQueryType()
                .getFields()
                .stream()
                .flatMap(it -> schema.getObject(it).getFields().stream())
                .filter(schema::isEnum)
                .findFirst()
                .orElse(null);
        if (field == null) {
            return CodeBlock.empty();
        }

        return toJOOQEnumConverter(field.getTypeName(), schema);
    }

    @Test
    @DisplayName("jOOQ enum converter")
    public void jOOQ() {
        compareCodeBlockResult("jOOQ", EXPECTED_JOOQ, DUMMY_ENUM_CONVERTED); // Makes sure it is the same for output as input.
    }

    @Test
    @DisplayName("String enum converter")
    public void string() {
        compareCodeBlockResult("string", EXPECTED_STRING, DUMMY_ENUM);
    }

    @Test
    @DisplayName("String enum converter with mixed case")
    public void stringMixedCase() {
        compareCodeBlockResult("stringMixedCase", EXPECTED_STRING_MIXED_CASE);
    }
}
