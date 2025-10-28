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
        assertGeneratedContentContains("oneInput", "_mi_in = _iv_env.getArgument(\"in\")", ".mutation(_mi_in)");
    }

    @Test
    @DisplayName("Single context input")
    void contextInput() {
        assertGeneratedContentContains("contextInput",
                "_iv_graphCtx = _iv_env.getGraphQlContext()",
                "String _cf_ctxField = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField\")",
                "mutation(_cf_ctxField)"
        );
    }

    @Test
    @DisplayName("Listed input")
    void listedInput() {
        assertGeneratedContentContains("listedInput", "in = _iv_env.getArgument(\"in\")", ".mutation(_mi_in)");
    }

    @Test
    @DisplayName("Two inputs")
    void multipleInputs() {
        assertGeneratedContentContains("multipleInputs", ".mutation(_mi_in0, _mi_in1)");
    }

    @Test
    @DisplayName("Record input")
    void recordInput() {
        assertGeneratedContentContains("recordInput", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(_mi_inRecord)");
    }

    @Test // Tests this only once as it is treated the same as jOOQ records otherwise.
    @DisplayName("Java record input")
    void javaRecordInput() {
        assertGeneratedContentContains("javaRecordInput", Set.of(DUMMY_INPUT_RECORD), ".mutation(_mi_inRecord)");
    }

    @Test
    @DisplayName("Two record inputs")
    void multipleRecordInputs() {
        assertGeneratedContentContains("multipleRecordInputs", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(_mi_in0Record, _mi_in1Record)");
    }

    @Test
    @DisplayName("Listed record input")
    void listedRecordInput() {
        assertGeneratedContentContains("listedRecordInput", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(_mi_inRecordList)");
    }

    @Test  // Not sure if this is the intended behaviour for such cases. The service will not be able to handle this type.
    @DisplayName("Input with non-record wrapper")
    void wrappedInput() {
        assertGeneratedContentContains("wrappedInput", ".mutation(_mi_in)");
    }

    @Test
    @DisplayName("JOOQ input record containing another input jOOQ record")
    void nestedInputRecord() {
        assertGeneratedContentContains("nestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE), ".mutation(_mi_in0Record, _mi_in1Record)");
    }

    @Test
    @DisplayName("JOOQ input record containing a listed input jOOQ record")
    void nestedListedInputRecord() {
        assertGeneratedContentContains(
                "nestedListedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in1RecordList = new ArrayList<",
                "in1RecordList = _iv_transform.",
                ".mutation(_mi_in0Record, _mi_in1RecordList)"
        );
    }

    @Test  // This logic gets so convoluted that it might just be better to remove the support for this.
    @DisplayName("Listed JOOQ input record containing a listed input jOOQ record")
    void nestedTwiceListedInputRecord() {
        assertGeneratedContentContains(
                "nestedTwiceListedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in1RecordList = new ArrayList<",
                "for (int _niit_in0 = 0; _niit_in0 < _mi_in0.size(); _niit_in0++) {" +
                        "var _nit_in0 = _mi_in0.get(_niit_in0);if (_nit_in0 == null) continue;" +
                        "var _mi_in1 = _nit_in0.getIn1();" +
                        "_mi_in1RecordList.addAll(_iv_transform.customerInputTableToJOOQRecord(_mi_in1, \"in0/in1\"",
                ".mutation(_mi_in0RecordList, _mi_in1RecordList)"
        );
    }

    @Test
    @DisplayName("Two layers of jOOQ records containing other records")
    void doubleNestedInputRecord() {
        assertGeneratedContentContains(
                "doubleNestedInputRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "in0Record = _iv_transform.",
                "var _mi_in1 = _mi_in0.getIn1();if (_mi_in1 != null) {_mi_in1Record = _iv_transform.wrapper1ToJOOQRecord(_mi_in1, \"in0/in1\");" +
                        "var _mi_in2 = _mi_in1.getIn2();_mi_in2Record = _iv_transform.customerInputTableToJOOQRecord(_mi_in2, \"in0/in1/in2\"",  // Inconsistent logic, but should work anyway.
                ".mutation(_mi_in0Record, _mi_in1Record, _mi_in2Record)"
        );
    }

    @Test
    @DisplayName("With service that returns a jOOQ record")
    void returningJOOQRecord() {
        assertGeneratedContentContains(
                "returningJOOQRecord", Set.of(CUSTOMER_TABLE),
                ".customerTableRecordToGraphType(_iv_response, \"\")"
        );
    }

    @Test
    @DisplayName("Service that returns a Java record")
    void returningJavaRecord() {
        assertGeneratedContentContains(
                "returningJavaRecord", Set.of(DUMMY_TYPE_RECORD),
                ".dummyTypeRecordToGraphType(_iv_response, \"\")"
        );
    }

    @Test
    @DisplayName("Validation enabled")
    void validation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains("recordInput", Set.of(CUSTOMER_INPUT_TABLE), "_iv_transform.validate();");
    }
}
