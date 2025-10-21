package no.sikt.graphitron.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("Mutation resolvers (old) - Resolvers for mutations except delete")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/edit/standard/withBatching";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test // Chose update for these tests, but there is no specific reason for this choice. Mutation type does not affect the resolver (except delete).
    @DisplayName("Single input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Record input with an additional scalar input")
    void withNonRecordInput() {
        assertGeneratedContentContains(
                "withNonRecordInput",
                "String email = _iv_env.getArgument(\"email\")",
                ".mutationForMutation(_iv_transform.getCtx(), inRecord, email)"
        );
    }

    @Test // Note that this will produce invalid code if the output is not also listed. Should throw exception for such cases.
    @DisplayName("Listed input")
    void listedInput() {
        assertGeneratedContentContains(
                "listedInput",
                ".getCtx(), inRecordList",
                "ctx, inRecordList, _iv_selectionSet)"
        );
    }

    @Test  // These kind of cases are ambiguous because there is no way to know which table to use in query.
    @Disabled("Case not supported.")
    @DisplayName("Two inputs")
    void multipleInputs() {
        assertGeneratedContentContains(
                "multipleInputs",
                ".getCtx(), in0Record, in1Record",
                "ctx, in0Record, in1Record, _iv_selectionSet"
        );
    }

    @Test
    @Disabled("Case not supported.")
    @DisplayName("Two listed inputs")
    void multipleListedInputs() {
        assertGeneratedContentContains(
                "multipleListedInputs",
                ".getCtx(), in0RecordList, in1RecordList",
                "ctx, in0RecordList, in1RecordList, _iv_selectionSet"
        );
    }

    @Test
    @Disabled("Case not supported.")
    @DisplayName("Two inputs, one listed and one not")
    void multipleMixedInputs() {
        assertGeneratedContentContains(
                "multipleMixedInputs",
                ".getCtx(), in0RecordList, in1Record",
                "ctx, in0RecordList, in1Record, _iv_selectionSet"
        );
    }

    @Test
    @DisplayName("Validation enabled")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("default", "_iv_transform.validate();");
    }
}
