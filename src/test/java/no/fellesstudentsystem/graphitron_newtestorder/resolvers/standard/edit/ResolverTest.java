package no.fellesstudentsystem.graphitron_newtestorder.resolvers.standard.edit;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Mutation resolvers - Resolvers for mutations")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/edit/standard";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateResolverClassGenerator(schema));
    }

    @Test // Chose update for these tests, but there is no specific reason for this choice. Mutation type does not affect the resolver.
    @DisplayName("Single input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Input that has no records")
    void noRecordInput() {
        assertThatThrownBy(() -> generateFiles("noRecordInput")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Input that has no usable IDs")
    void noIDInput() {
        assertThatThrownBy(() -> generateFiles("noIDInput")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Record input with an additional scalar input")
    void withNonRecordInput() {
        assertGeneratedContentContains(
                "withNonRecordInput",
                ", String email,",
                ".mutation(transform.getCtx(), inRecord, email)"
        );
    }

    @Test // Note that this will produce invalid code if the output is not also listed. Should throw exception for such cases.
    @DisplayName("Listed input")
    void listedInput() {
        assertGeneratedContentContains(
                "listedInput",
                "List<CustomerInputTable> in,",
                "inRecordList =",
                "in, \"in\"",
                ".getCtx(), inRecordList",
                ".completedFuture(inRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()"
        );
    }

    @Test
    @DisplayName("Two inputs")
    void multipleInputs() {
        assertGeneratedContentContains(
                "multipleInputs",
                "CustomerInputTable in0, CustomerInputTable in1,",
                "in0Record =",
                "in0, \"in0\"",
                "in1Record =",
                "in1, \"in1\"",
                ".getCtx(), in0Record, in1Record",
                ".completedFuture(in0Record.getId()"  // It selects the first ID input for this, there is no smart logic here.
        );
    }

    @Test
    @DisplayName("Two listed inputs")
    void multipleListedInputs() {
        assertGeneratedContentContains(
                "multipleListedInputs",
                "List<CustomerInputTable> in0, List<CustomerInputTable> in1,",
                "in0RecordList =",
                "in0, \"in0\"",
                "in1RecordList =",
                "in1, \"in1\"",
                ".getCtx(), in0RecordList, in1RecordList",
                ".completedFuture(in0RecordList.stream().map(it -> it.getId()).collect("
        );
    }

    @Test
    @DisplayName("Two inputs, one listed and one not")
    void multipleMixedInputs() {
        assertGeneratedContentContains(
                "multipleMixedInputs",
                "List<CustomerInputTable> in0, CustomerInputTable in1,",
                "in0RecordList =",
                "in0, \"in0\"",
                "in1Record =",
                "in1, \"in1\"",
                ".getCtx(), in0RecordList, in1Record",
                ".completedFuture(in0RecordList.stream().map(it -> it.getId()).collect("
        );
    }

    @Test  // Not sure if this is the intended behaviour for such cases. The mutation does not try to unwrap the input.
    @DisplayName("Input with non-record wrapper containing a record")
    void wrappedInput() {
        assertThatThrownBy(() -> generateFiles("wrappedInput")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Return field that is not ID")
    void returningString() {
        assertGeneratedContentContains(
                "returningString",
                ".completedFuture(inRecord.getId()" // Does not really try to find the right field.
        );
    }

    @Test
    @DisplayName("Response with ID")
    void responseType() {
        assertGeneratedContentContains(
                "responseType",
                "CompletableFuture<Response> mutation(",
                "response = new Response()",
                "response.setId(inRecord.getId()",
                ".completedFuture(response"
        );
    }

    @Test // Note that this will produce invalid code if the input is not also listed. Should throw exception for such cases.
    @DisplayName("Listed response with ID")
    void responseTypeListed() {
        assertGeneratedContentContains(
                "responseTypeListed",
                "CompletableFuture<List<Response>> mutation(",
                "responseList = new ArrayList<Response>()",
                "for (var itInRecordList : inRecordList) {" +
                        "var response = new Response();" +
                        "response.setId(itInRecordList.getId());" +
                        "responseList.add(response);}",
                ".completedFuture(responseList"
        );
    }

    @Test // Note that this will produce invalid code if the input is not also listed. Should throw exception for such cases.
    @DisplayName("Response with listed ID")
    void responseTypeListedIds() {
        assertGeneratedContentContains(
                "responseTypeListedIds",
                ".setId(inRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()"
        );
    }

    @Test
    @DisplayName("Response with two fields")
    void responseMultipleFields() {
        assertGeneratedContentContains(
                "responseMultipleFields",
                "new Response();response.setId(inRecord.getId());return"  // Does not know how to set multiple fields.
        );
    }

    @Test
    @DisplayName("Response containing a record type")
    void responseRecord() {
        assertGeneratedContentMatches("responseRecord");
    }

    @Test // If input is not iterable, this will produce invalid code.
    @DisplayName("Response containing a listed record type")
    void responseRecordListed() {
        assertGeneratedContentContains(
                "responseRecordListed",
                "inRecordCustomer = getResponseCustomer(transform, inRecordList,",
                "customerList = new ArrayList<Customer",
                "for (var itInRecordList : inRecordList) {customerList.add(inRecordCustomer.get(itInRecordList.getId()));}",
                "response.setCustomer(customerList)",
                "Map<String, Customer> getResponseCustomer",
                "List<CustomerRecord> idContainer,",
                "return Map.of()",
                ", idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet()),"
        );
    }

    @Test // This probably does not make much sense in practice.
    @DisplayName("Listed response containing a listed record type")
    void responseListedRecordListed() {
        assertGeneratedContentContains(
                "responseListedRecordListed",
                "responseList = new ArrayList<Response>()",
                "customerList = new ArrayList<Customer>()",
                "customerList.add(inRecordCustomer.get(itInRecordList.getId()",
                "response.setCustomer(customerList",
                "responseList.add(response"
        );
    }

    @Test
    @DisplayName("Nested response containing a record type")
    void nestedResponseRecord() {
        assertGeneratedContentContains(
                "nestedResponseRecord",
                "new Response1",
                "new Response2()",
                "response2.setCustomer(inRecordCustomer)",
                "response1.setResponse2(response2)"
        );
    }

    @Test
    @DisplayName("Nested and listed response containing a record type")
    void nestedListedResponseRecord() {
        assertGeneratedContentContains(
                "nestedListedResponseRecord",
                "new Response1",
                "new ArrayList<Response2>",
                "for (var itInRecordList : inRecordList)",
                "response2.setCustomer(inRecordCustomer.get(itInRecordList.getId()",
                "response1.setResponse2(response2List)"
        );
    }

    @Test
    @DisplayName("Nested response containing a listed record type")
    void nestedResponseListedRecord() {
        assertGeneratedContentContains(
                "nestedResponseListedRecord",
                "new Response1",
                "new ArrayList<Response2>",  // Note, this is invalid code even though the schema should be legal. We get two loops here when we expect only one.
                "new ArrayList<Customer>",
                "customerList.add(inRecordCustomer.get(itInRecordList.getId()",
                "response2.setCustomer(customerList"
        );
    }

    @Test  // Note, special case that probably should not be supported.
    @DisplayName("Listed response while input is not listed")
    void responseListedInputUnlisted() {
        assertGeneratedContentContains(
                "responseListedInputUnlisted",
                "response2.setCustomer(inRecordCustomer",
                "response1.setResponse2(List.of(response2"
        );
    }

    @Test  // Note, special case that probably should not be supported.
    @DisplayName("Response not listed while input is listed")
    void responseUnlistedInputListed() {
        assertGeneratedContentContains(
                "responseUnlistedInputListed",
                "for (var itInRecordList : inRecordList)",
                "response2.setCustomer(inRecordCustomer.get(itInRecordList.getId()",
                "response1.setResponse2(response2List.stream().findFirst().orElse(List.of()"
        );
    }

    @Test
    @DisplayName("Validation enabled")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("default", "transform.validate();");
    }
}
