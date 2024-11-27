package no.sikt.graphitron.code;

import com.squareup.javapoet.CodeBlock;
import no.sikt.graphitron.common.CodeBlockTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.MAPPER_RECORD_ENUM;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM_CONVERTED;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.toGraphEnumConverter;

@DisplayName("Graph enums - Enum conversion when mapping enums")
public class MapperEnumConversionFunctionTest extends CodeBlockTest {
    @Override
    protected String getSubpath() {
        return "enums";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, MAPPER_RECORD_ENUM);
    }

    @Override
    protected CodeBlock makeCodeBlock(ProcessedSchema schema) {
        var field = schema
                .getRecordType("Dummy")
                .getFields()
                .stream()
                .filter(schema::isEnum)
                .findFirst()
                .orElse(null);
        if (field == null) {
            return empty();
        }

        return toGraphEnumConverter(field.getTypeName(), CodeBlock.of(field.getName()), field.isIterableWrapped(), field.isInput(), schema);
    }

    @Test
    @DisplayName("Graph jOOQ enum converter")
    public void toGraphJOOQ() {
        compareCodeBlockResult(
                "tograph/jOOQ",
                "e == null ? null : java.util.Map.of(" +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, fake.graphql.example.model.DummyEnumConverted.A," +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, fake.graphql.example.model.DummyEnumConverted.B," +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C, fake.graphql.example.model.DummyEnumConverted.C" +
                        ").getOrDefault(e, null)",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("Graph jOOQ listed enum converter")
    public void toGraphJOOQListed() {
        compareCodeBlockResult(
                "tograph/jOOQListed",
                "e == null ? null : e.stream().map(itDummyEnumConverted -> java.util.Map.of(" +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, fake.graphql.example.model.DummyEnumConverted.A," +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, fake.graphql.example.model.DummyEnumConverted.B," +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C, fake.graphql.example.model.DummyEnumConverted.C" +
                        ").getOrDefault(itDummyEnumConverted, null)).collect(java.util.stream.Collectors.toList())",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("Graph string enum converter")
    public void toGraphString() {
        compareCodeBlockResult(
                "tograph/string",
                "e == null ? null : java.util.Map.of(" +
                        "\"A\", fake.graphql.example.model.DummyEnum.A," +
                        "\"B\", fake.graphql.example.model.DummyEnum.B," +
                        "\"C\", fake.graphql.example.model.DummyEnum.C" +
                        ").getOrDefault(e, null)",
                DUMMY_ENUM
        );
    }

    @Test
    @DisplayName("Graph string listed enum converter")
    public void toGraphStringListed() {
        compareCodeBlockResult(
                "tograph/stringListed",
                "e == null ? null : e.stream().map(itDummyEnum -> java.util.Map.of(" +
                        "\"A\", fake.graphql.example.model.DummyEnum.A," +
                        "\"B\", fake.graphql.example.model.DummyEnum.B," +
                        "\"C\", fake.graphql.example.model.DummyEnum.C" +
                        ").getOrDefault(itDummyEnum, null)).collect(java.util.stream.Collectors.toList())",
                DUMMY_ENUM
        );
    }

    @Test
    @DisplayName("Record jOOQ enum converter")
    public void toRecordJOOQ() {
        compareCodeBlockResult(
                "torecord/jOOQ",
                "e == null ? null : java.util.Map.of(" +
                        "fake.graphql.example.model.DummyEnumConverted.A, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A," +
                        "fake.graphql.example.model.DummyEnumConverted.B, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B," +
                        "fake.graphql.example.model.DummyEnumConverted.C, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C" +
                        ").getOrDefault(e, null)",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("jOOQ listed enum converter")
    public void toRecordJOOQListed() {
        compareCodeBlockResult(
                "torecord/jOOQListed",
                "e == null ? null : e.stream().map(itDummyEnumConverted -> java.util.Map.of(" +
                        "fake.graphql.example.model.DummyEnumConverted.A, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A," +
                        "fake.graphql.example.model.DummyEnumConverted.B, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B," +
                        "fake.graphql.example.model.DummyEnumConverted.C, no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C" +
                        ").getOrDefault(itDummyEnumConverted, null)).collect(java.util.stream.Collectors.toList())",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("Record string enum converter")
    public void toRecordString() {
        compareCodeBlockResult(
                "torecord/string",
                "e == null ? null : java.util.Map.of(" +
                        "fake.graphql.example.model.DummyEnum.A, \"A\"," +
                        "fake.graphql.example.model.DummyEnum.B, \"B\"," +
                        "fake.graphql.example.model.DummyEnum.C, \"C\"" +
                        ").getOrDefault(e, null)",
                DUMMY_ENUM
        );
    }

    @Test
    @DisplayName("Record string listed enum converter")
    public void toRecordStringListed() {
        compareCodeBlockResult(
                "torecord/stringListed",
                "e == null ? null : e.stream().map(itDummyEnum -> java.util.Map.of(" +
                        "fake.graphql.example.model.DummyEnum.A, \"A\"," +
                        "fake.graphql.example.model.DummyEnum.B, \"B\"," +
                        "fake.graphql.example.model.DummyEnum.C, \"C\"" +
                        ").getOrDefault(itDummyEnum, null)).collect(java.util.stream.Collectors.toList())",
                DUMMY_ENUM
        );
    }
}
