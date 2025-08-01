package no.sikt.graphitron.code;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.common.CodeBlockTest;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM_CONVERTED;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.toJOOQEnumConverter;

@DisplayName("Query input enums - Enum conversion for queries")
public class InputEnumConversionFunctionTest extends CodeBlockTest {
    public static final String EXPECTED_JOOQ = ".convert(fake.graphql.example.model.DummyEnumConverted.class, " +
            "s -> no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(s, " +
            "java.util.List.of(no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C), " +
            "java.util.List.of(fake.graphql.example.model.DummyEnumConverted.A, fake.graphql.example.model.DummyEnumConverted.B, fake.graphql.example.model.DummyEnumConverted.C))," +
            "s -> no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(s, " +
            "java.util.List.of(fake.graphql.example.model.DummyEnumConverted.A, fake.graphql.example.model.DummyEnumConverted.B, fake.graphql.example.model.DummyEnumConverted.C), " +
            "java.util.List.of(no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C)))";
    public static final String EXPECTED_STRING = ".convert(fake.graphql.example.model.DummyEnum.class," +
            "s -> no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(s, " +
            "java.util.List.of(\"A\", \"B\", \"C\"), " +
            "java.util.List.of(fake.graphql.example.model.DummyEnum.A, fake.graphql.example.model.DummyEnum.B, fake.graphql.example.model.DummyEnum.C))," +
            "s -> no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(s, " +
            "java.util.List.of(fake.graphql.example.model.DummyEnum.A, fake.graphql.example.model.DummyEnum.B, fake.graphql.example.model.DummyEnum.C), " +
            "java.util.List.of(\"A\", \"B\", \"C\")))";
    public static final String EXPECTED_STRING_MIXED_CASE = ".convert(fake.graphql.example.model.MixedCaseEnum.class," +
            "s -> no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(s, " +
            "java.util.List.of(\"UPPER\", \"lower\", \"mIxEd\"), " +
            "java.util.List.of(fake.graphql.example.model.MixedCaseEnum.UPPER, fake.graphql.example.model.MixedCaseEnum.LOWER, fake.graphql.example.model.MixedCaseEnum.MIXED))," +
            "s -> no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(s, " +
            "java.util.List.of(fake.graphql.example.model.MixedCaseEnum.UPPER, fake.graphql.example.model.MixedCaseEnum.LOWER, fake.graphql.example.model.MixedCaseEnum.MIXED), " +
            "java.util.List.of(\"UPPER\", \"lower\", \"mIxEd\")))";

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
            return CodeBlock.empty();
        }

        return toJOOQEnumConverter(field.getTypeName(), schema);
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

    @Test
    @DisplayName("String enum converter with mixed case")
    public void stringMixedCase() {
        compareCodeBlockResult("stringMixedCase", EXPECTED_STRING_MIXED_CASE);
    }
}
