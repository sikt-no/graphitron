package no.sikt.graphitron.datafetchers.services.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.CONTEXT_SERVICE;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_MUTATION_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Mutation resolvers - Resolvers with services")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/edit/services";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_MUTATION_SERVICE, CONTEXT_SERVICE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("No inputs")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Single input")
    void oneInput() {
        assertGeneratedContentContains("oneInput", "String in = env.getArgument(\"in\")", ".mutation(in)");
    }

    @Test
    @DisplayName("Single context input")
    void contextInput() {
        assertGeneratedContentContains("contextInput", "_graphCtx = env.getGraphQlContext()", "_c_ctxField = ((String) _graphCtx.get(\"ctxField\"))", "mutation(_c_ctxField)");
    }

    @Test
    @DisplayName("Listed input")
    void listedInput() {
        assertGeneratedContentContains("listedInput", "List<String> in = env.getArgument(\"in\")", ".mutation(in)");
    }

    @Test
    @DisplayName("Two inputs")
    void multipleInputs() {
        assertGeneratedContentContains("multipleInputs", ".mutation(in0, in1)");
    }

    @Test
    @DisplayName("Record input")
    void recordInput() {
        assertGeneratedContentContains("recordInput", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(inRecord)");
    }

    @Test // Tests this only once as it is treated the same as jOOQ records otherwise.
    @DisplayName("Java record input")
    void javaRecordInput() {
        assertGeneratedContentContains("javaRecordInput", Set.of(DUMMY_INPUT_RECORD), ".mutation(inRecord)");
    }

    @Test
    @DisplayName("Two record inputs")
    void multipleRecordInputs() {
        assertGeneratedContentContains("multipleRecordInputs", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(in0Record, in1Record)");
    }

    @Test
    @DisplayName("Listed record input")
    void listedRecordInput() {
        assertGeneratedContentContains("listedRecordInput", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(inRecordList)");
    }

    @Test  // Not sure if this is the intended behaviour for such cases. The service will not be able to handle this type.
    @DisplayName("Input with non-record wrapper")
    void wrappedInput() {
        assertGeneratedContentContains("wrappedInput", ".mutation(in)");
    }

    @Test
    @DisplayName("JOOQ input record containing another input jOOQ record")
    void nestedInputRecord() {
        assertGeneratedContentContains("nestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(in0Record, in1Record)");
    }

    @Test
    @DisplayName("JOOQ input record containing a listed input jOOQ record")
    void nestedListedInputRecord() {
        assertGeneratedContentContains(
                "nestedListedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in1RecordList = new ArrayList<",
                "in1RecordList = transform.",
                ".mutation(in0Record, in1RecordList)"
        );
    }

    @Test  // This logic gets so convoluted that it might just be better to remove the support for this.
    @DisplayName("Listed JOOQ input record containing a listed input jOOQ record")
    void nestedTwiceListedInputRecord() {
        assertGeneratedContentContains(
                "nestedTwiceListedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in1RecordList = new ArrayList<",
                "for (int itIn0Index = 0; itIn0Index < in0.size(); itIn0Index++) {" +
                        "var itIn0 = in0.get(itIn0Index);if (itIn0 == null) continue;" +
                        "var in1 = itIn0.getIn1();" +
                        "in1RecordList.addAll(transform.customerInputTableToJOOQRecord(in1, \"in0/in1\"",
                ".mutation(in0RecordList, in1RecordList)"
        );
    }

    @Test
    @DisplayName("Two layers of jOOQ records containing other records")
    void doubleNestedInputRecord() {
        assertGeneratedContentContains(
                "doubleNestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in0Record = transform.",
                "var in1 = in0.getIn1();if (in1 != null) {in1Record = transform.wrapper1ToJOOQRecord(in1, \"in0/in1\");" +
                        "var in2 = in1.getIn2();in2Record = transform.customerInputTableToJOOQRecord(in2, \"in0/in1/in2\"",  // Inconsistent logic, but should work anyway.
                ".mutation(in0Record, in1Record, in2Record)"
        );
    }

    @Test
    @DisplayName("With service that returns a jOOQ record")
    void returningJOOQRecord() {
        assertGeneratedContentContains(
                "returningJOOQRecord", Set.of(CUSTOMER_TABLE),
                ".customerTableRecordToGraphType(response, \"\")"
        );
    }

    @Test
    @DisplayName("Service that returns a Java record")
    void returningJavaRecord() {
        assertGeneratedContentContains(
                "returningJavaRecord", Set.of(DUMMY_TYPE_RECORD),
                ".dummyTypeRecordToGraphType(response, \"\")"
        );
    }

    @Test
    @DisplayName("Validation enabled")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("recordInput", Set.of(CUSTOMER_INPUT_TABLE), "transform.validate();");
    }
}
