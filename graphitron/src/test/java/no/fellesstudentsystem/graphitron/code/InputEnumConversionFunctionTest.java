package no.fellesstudentsystem.graphitron.code;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.common.CodeBlockTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM_CONVERTED;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.toJOOQEnumConverter;

@DisplayName("Query input enums - Enum conversion for queries")
public class InputEnumConversionFunctionTest extends CodeBlockTest {
    public static final String EXPECTED_JOOQ = ".convert(fake.graphql.example.model.DummyEnumConverted.class," +
            " s -> s == null ? null : java.util.Map.of(" +
            "no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, fake.graphql.example.model.DummyEnumConverted.A," +
            " no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, fake.graphql.example.model.DummyEnumConverted.B," +
            " no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C, fake.graphql.example.model.DummyEnumConverted.C" +
            ").getOrDefault(s, null), s -> s == null ? null : java.util.Map.of(" +
            "fake.graphql.example.model.DummyEnumConverted.A, no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A," +
            " fake.graphql.example.model.DummyEnumConverted.B, no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B," +
            " fake.graphql.example.model.DummyEnumConverted.C, no.fellesstudentsystem.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C" +
            ").getOrDefault(s, null))";
    public static final String EXPECTED_STRING = ".convert(fake.graphql.example.model.DummyEnum.class," +
            " s -> s == null ? null : java.util.Map.of(" +
            "\"A\", fake.graphql.example.model.DummyEnum.A," +
            " \"B\", fake.graphql.example.model.DummyEnum.B," +
            " \"C\", fake.graphql.example.model.DummyEnum.C" +
            ").getOrDefault(s, null), s -> s == null ? null : java.util.Map.of(" +
            "fake.graphql.example.model.DummyEnum.A, \"A\"," +
            " fake.graphql.example.model.DummyEnum.B, \"B\"," +
            " fake.graphql.example.model.DummyEnum.C, \"C\"" +
            ").getOrDefault(s, null))";

    @Override
    protected String getSubpath() {
        return "queries/fetch/enums/input";
    }

    @Override
    protected CodeBlock makeCodeBlock(ProcessedSchema schema) {
        var field = schema
                .getQueryType()
                .getFields()
                .stream()
                .flatMap(it -> it.getArguments().stream())
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
        compareCodeBlockResult("jOOQ", EXPECTED_JOOQ, DUMMY_ENUM_CONVERTED);
    }

    @Test
    @DisplayName("String enum converter")
    public void string() {
        compareCodeBlockResult("string", EXPECTED_STRING, DUMMY_ENUM);
    }
}
