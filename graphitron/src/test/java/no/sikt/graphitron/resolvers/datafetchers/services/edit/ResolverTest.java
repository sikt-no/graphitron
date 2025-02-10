package no.sikt.graphitron.resolvers.datafetchers.services.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.update.UpdateClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_MUTATION_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Mutation resolvers - Resolvers with services")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/edit/services";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_MUTATION_SERVICE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateClassGenerator(schema));
    }

    @Test
    @DisplayName("No inputs")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Single input")
    void oneInput() {
        assertGeneratedContentContains("oneInput", "in = ((String) _args.get(\"in\"))", ".mutation(in)");
    }

    @Test
    @DisplayName("Listed input")
    void listedInput() {
        assertGeneratedContentContains("listedInput", "in = ((List<String>) _args.get(\"in\"))", ".mutation(in)");
    }

    @Test
    @DisplayName("Two inputs")
    void multipleInputs() {
        assertGeneratedContentContains(
                "multipleInputs",
                "in0 = ((String) _args.get(\"in0\"))",
                "in1 = ((String) _args.get(\"in1\"))",
                ".mutation(in0, in1)"
        );
    }

    @Test
    @DisplayName("Record input")
    void recordInput() {
        assertGeneratedContentContains(
                "recordInput", Set.of(CUSTOMER_INPUT_TABLE),
                "in = ResolverHelpers.transformDTO(_args.get(\"in\"), CustomerInputTable.class)",
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                ".mutation(inRecord)"
        );
    }

    @Test // Tests this only once as it is treated the same as jOOQ records otherwise.
    @DisplayName("Java record input")
    void javaRecordInput() {
        assertGeneratedContentContains(
                "javaRecordInput", Set.of(DUMMY_INPUT_RECORD),
                "in = ResolverHelpers.transformDTO(_args.get(\"in\"), DummyInputRecord.class)",
                "inRecord = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                ".mutation(inRecord)"
        );
    }

    @Test
    @DisplayName("Two record inputs")
    void multipleRecordInputs() {
        assertGeneratedContentContains(
                "multipleRecordInputs", Set.of(CUSTOMER_INPUT_TABLE),
                "in0Record = transform.customerInputTableToJOOQRecord(in0, \"in0\")",
                "in1Record = transform.customerInputTableToJOOQRecord(in1, \"in1\")",
                ".mutation(in0Record, in1Record)"
        );
    }

    @Test
    @DisplayName("Listed record input")
    void listedRecordInput() {
        assertGeneratedContentContains(
                "listedRecordInput", Set.of(CUSTOMER_INPUT_TABLE),
                "inRecordList = transform.customerInputTableToJOOQRecord(in, \"in\")",
                ".mutation(inRecordList)"
        );
    }

    @Test  // Not sure if this is the intended behaviour for such cases. The service will not be able to handle this type.
    @DisplayName("Input with non-record wrapper")
    void wrappedInput() {
        assertGeneratedContentContains("wrappedInput", "in = ResolverHelpers.transformDTO(_args.get(\"in\"), Input.class)", ".mutation(in)");
    }

    @Test
    @DisplayName("JOOQ input record containing another input jOOQ record")
    void nestedInputRecord() {
        assertGeneratedContentContains(
                "nestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in0Record = transform.",
                "in1Record = new CustomerRecord();in1Record.attach(transform.getCtx().configuration()",
                "if (in0 != null) {var in1 = in0.getIn1();in1Record = transform.customerInputTableToJOOQRecord(in1, \"in0/in1\")",
                ".mutation(in0Record, in1Record)"
        );
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
                "customerTable = transform.customerTableRecordToGraphType(mutation, \"\")",
                ".completedFuture(customerTable)"
        );
    }

    @Test
    @DisplayName("With service that returns a jOOQ record")
    void returningJavaRecord() {
        assertGeneratedContentContains(
                "returningJavaRecord", Set.of(DUMMY_TYPE_RECORD),
                "dummyTypeRecord = transform.dummyTypeRecordToGraphType(mutation, \"\")",
                ".completedFuture(dummyTypeRecord)"
        );
    }

    @Test  // The only difference right now is that the helper variable appends "List" to the name.
    @DisplayName("With service that returns a listed record")
    void returningListedRecord() {
        assertGeneratedContentContains(
                "returningListedRecord", Set.of(CUSTOMER_TABLE),
                "customerTableList =",
                ".completedFuture(customerTableList)"
        );
    }

    @Test
    @DisplayName("Response containing one ID")
    void responseID() {
        assertGeneratedContentContains(
                "responseID",
                "DataFetcher<CompletableFuture<Response>>",
                "response = new Response()",
                "if (mutation != null && transform.getSelect().contains(\"id\")) {response.setId(mutation);}",
                ".completedFuture(response)"
        );
    }

    @Test
    @DisplayName("Listed response")
    void listedResponse() {
        assertGeneratedContentContains(
                "listedResponse",
                "DataFetcher<CompletableFuture<List<Response>>>",
                "response = new Response()", // TODO: Does not actually declare and fill a list.
                ".contains(\"id\")",
                "response.setId(itMutation)",
                ".completedFuture(responseList)"
        );
    }

    @Test
    @DisplayName("Response containing a record")
    void responseRecord() {
        assertGeneratedContentContains(
                "responseRecord", Set.of(CUSTOMER_TABLE),
                ".contains(\"customer\"",
                "response.setCustomer(transform.customerTableRecordToGraphType(mutation, \"customer\")"
        );
    }

    @Test
    @DisplayName("Response containing a record fetched by ID")
    void responseFetchByID() {
        assertGeneratedContentContains(
                "responseFetchByID", Set.of(NODE),
                "response.setCustomer(CustomerDBQueries.customerForNode(" +
                        "transform.getCtx(), Set.of(mutation.getId()), transform.getSelect().withPrefix(\"customer\")).values().stream().findFirst().orElse(null));"
        );
    }

    @Test
    @DisplayName("Response containing a list of records fetched by ID")
    void responseFetchByIDList() {
        assertGeneratedContentContains(
                "responseFetchByIDList", Set.of(NODE),
                "customerForNode = CustomerDBQueries.customerForNode(" +
                        "transform.getCtx(), mutation.stream().map(it -> it.getId()).collect(Collectors.toSet()), transform.getSelect().withPrefix(\"customer\"));" +
                "response.setCustomer(mutation.stream().map(it -> customerForNode.get(it.getId())).collect(Collectors.toList()"
        );
    }

    @Test
    @DisplayName("Response with non-record wrapper")
    void wrappedResponse() {
        assertGeneratedContentContains(
                "wrappedResponse",
                "wrapper = new Wrapper()",
                ".contains(\"response\"",
                "response = new Response()",
                ".contains(\"response/id\")",
                "response.setId(response)",  // TODO: This should set "mutation", not "response". This code will not work. Also, the wrapper is never filled.
                ".completedFuture(wrapper)"
        );
    }

    @Test
    @DisplayName("Validation enabled")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("recordInput", Set.of(CUSTOMER_INPUT_TABLE), "transform.validate();");
    }

    @Test
    @DisplayName("Service method that can not be found")
    void undefinedServiceMethod() {
        assertThatThrownBy(() -> generateFiles("undefinedServiceMethod"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Service reference no.sikt.graphitron.codereferences.services.ResolverMutationService does not contain method named UNDEFINED");
    }

    @Test
    @DisplayName("Neither service nor mutation directive is set on a mutation")
    void noHandlingSet() {
        assertThatThrownBy(() -> generateFiles("noHandlingSet"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mutation 'mutation' is set to generate, but has neither a service nor mutation type set.");
    }
}
