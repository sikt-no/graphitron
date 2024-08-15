package no.fellesstudentsystem.graphitron_newtestorder.code;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron_newtestorder.CodeBlockTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.toJOOQEnumConverter;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_ENUM;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_ENUM_CONVERTED;
import static no.fellesstudentsystem.graphitron_newtestorder.code.InputEnumConversionFunctionTest.EXPECTED_JOOQ;
import static no.fellesstudentsystem.graphitron_newtestorder.code.InputEnumConversionFunctionTest.EXPECTED_STRING;

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
            return empty();
        }

        return toJOOQEnumConverter(field.getTypeName(), field.isIterableWrapped(), schema);
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
}
