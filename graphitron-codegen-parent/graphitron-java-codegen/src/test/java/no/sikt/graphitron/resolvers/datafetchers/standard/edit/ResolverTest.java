package no.sikt.graphitron.resolvers.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("Mutation resolvers - Resolvers for mutations")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/edit/standard";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test // Chose update for these tests, but there is no specific reason for this choice. Mutation type does not affect the resolver.
    @DisplayName("Single input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Record input with an additional scalar input")
    void withNonRecordInput() {
        assertGeneratedContentContains(
                "withNonRecordInput",
                "email = ((String) _args.get(\"email\"))",
                ".mutationForMutation(transform.getCtx(), inRecord, email)"
        );
    }

    @Test // Note that this will produce invalid code if the output is not also listed. Should throw exception for such cases.
    @DisplayName("Listed input")
    void listedInput() {
        assertGeneratedContentContains(
                "listedInput",
                ".getCtx(), inRecordList",
                "ctx, inRecordList, selectionSet)"
        );
    }

    @Test  // These kind of cases are ambiguous because there is no way to know which table to use in query.
    @Disabled("Case not supported.")
    @DisplayName("Two inputs")
    void multipleInputs() {
        assertGeneratedContentContains(
                "multipleInputs",
                ".getCtx(), in0Record, in1Record",
                "ctx, in0Record, in1Record, selectionSet"
        );
    }

    @Test
    @Disabled("Case not supported.")
    @DisplayName("Two listed inputs")
    void multipleListedInputs() {
        assertGeneratedContentContains(
                "multipleListedInputs",
                ".getCtx(), in0RecordList, in1RecordList",
                "ctx, in0RecordList, in1RecordList, selectionSet"
        );
    }

    @Test
    @Disabled("Case not supported.")
    @DisplayName("Two inputs, one listed and one not")
    void multipleMixedInputs() {
        assertGeneratedContentContains(
                "multipleMixedInputs",
                ".getCtx(), in0RecordList, in1Record",
                "ctx, in0RecordList, in1Record, selectionSet"
        );
    }

    @Test
    @DisplayName("Validation enabled")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("default", "transform.validate();");
    }
}
