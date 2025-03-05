package no.sikt.graphitron.code;

import com.palantir.javapoet.CodeBlock;
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

        return toGraphEnumConverter(field.getTypeName(), CodeBlock.of(field.getName()), field.isInput(), schema);
    }

    @Test
    @DisplayName("Graph jOOQ enum converter")
    public void toGraphJOOQ() {
        compareCodeBlockResult(
                "tograph/jOOQ",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(" +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C), " +
                        "java.util.List.of(" +
                        "fake.graphql.example.model.DummyEnumConverted.A, " +
                        "fake.graphql.example.model.DummyEnumConverted.B, " +
                        "fake.graphql.example.model.DummyEnumConverted.C))",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("Graph jOOQ listed enum converter")
    public void toGraphJOOQListed() {
        compareCodeBlockResult(
                "tograph/jOOQListed",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(" +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C), " +
                        "java.util.List.of(" +
                        "fake.graphql.example.model.DummyEnumConverted.A, " +
                        "fake.graphql.example.model.DummyEnumConverted.B, " +
                        "fake.graphql.example.model.DummyEnumConverted.C))",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("Graph string enum converter")
    public void toGraphString() {
        compareCodeBlockResult(
                "tograph/string",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(\"A\", \"B\", \"C\"), " +
                        "java.util.List.of(fake.graphql.example.model.DummyEnum.A, fake.graphql.example.model.DummyEnum.B, fake.graphql.example.model.DummyEnum.C))",
                DUMMY_ENUM
        );
    }

    @Test
    @DisplayName("Graph string listed enum converter")
    public void toGraphStringListed() {
        compareCodeBlockResult(
                "tograph/stringListed",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(\"A\", \"B\", \"C\"), " +
                        "java.util.List.of(fake.graphql.example.model.DummyEnum.A, fake.graphql.example.model.DummyEnum.B, fake.graphql.example.model.DummyEnum.C))",
                DUMMY_ENUM
        );
    }

    @Test
    @DisplayName("Record jOOQ enum converter")
    public void toRecordJOOQ() {
        compareCodeBlockResult(
                "torecord/jOOQ",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(" +
                        "fake.graphql.example.model.DummyEnumConverted.A, " +
                        "fake.graphql.example.model.DummyEnumConverted.B, " +
                        "fake.graphql.example.model.DummyEnumConverted.C), " +
                        "java.util.List.of(" +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C))",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("jOOQ listed enum converter")
    public void toRecordJOOQListed() {
        compareCodeBlockResult(
                "torecord/jOOQListed",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(" +
                        "fake.graphql.example.model.DummyEnumConverted.A, " +
                        "fake.graphql.example.model.DummyEnumConverted.B, " +
                        "fake.graphql.example.model.DummyEnumConverted.C), " +
                        "java.util.List.of(" +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.A, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.B, " +
                        "no.sikt.graphitron.codereferences.dummyreferences.DummyJOOQEnum.C))",
                DUMMY_ENUM_CONVERTED
        );
    }

    @Test
    @DisplayName("Record string enum converter")
    public void toRecordString() {
        compareCodeBlockResult(
                "torecord/string",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(fake.graphql.example.model.DummyEnum.A, fake.graphql.example.model.DummyEnum.B, fake.graphql.example.model.DummyEnum.C), " +
                        "java.util.List.of(\"A\", \"B\", \"C\"))",
                DUMMY_ENUM
        );
    }

    @Test
    @DisplayName("Record string listed enum converter")
    public void toRecordStringListed() {
        compareCodeBlockResult(
                "torecord/stringListed",
                "no.sikt.graphql.helpers.query.QueryHelper.makeEnumMap(e, " +
                        "java.util.List.of(fake.graphql.example.model.DummyEnum.A, fake.graphql.example.model.DummyEnum.B, fake.graphql.example.model.DummyEnum.C), " +
                        "java.util.List.of(\"A\", \"B\", \"C\"))",
                DUMMY_ENUM
        );
    }
}
