package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Schema validation - Unknown and unresolvable values")
public class UnknownTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "unknown";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_CONDITION, RESOLVER_MUTATION_SERVICE, DUMMY_SERVICE, JAVA_RECORD_CUSTOMER);
    }

    @Test
    @DisplayName("Enum reference that can not be resolved")
    void unknownEnum() {
        assertErrorsContain("enum", "No enum with name 'UNKNOWN_ENUM' found.");
    }

    @Test
    @DisplayName("Type table")
    void typeTable() {
        assertErrorsContain("typeTable", "No table with name \"UNKNOWN_TABLE\" found.");
    }

    @Test
    @DisplayName("Input table")
    void inputTable() {
        assertErrorsContain("inputTable", "No table with name \"UNKNOWN_TABLE\" found.");
    }

    @Test
    @DisplayName("Field reference table")
    void fieldReferenceTable() {
        assertErrorsContain("fieldReferenceTable", "No table with name \"UNKNOWN_TABLE\" found.");
    }

    @Test
    @DisplayName("Input field reference table")
    void inputFieldReferenceTable() {
        assertErrorsContain("inputFieldReferenceTable", "No table with name \"UNKNOWN_TABLE\" found.");
    }

    @Test
    @DisplayName("Argument reference table")
    void argumentReferenceTable() {
        assertErrorsContain("argumentReferenceTable", "No table with name \"UNKNOWN_TABLE\" found.");
    }

    @Test
    @DisplayName("Field reference key")
    void fieldReferenceKey() {
        assertErrorsContain("fieldReferenceKey", "No key with name \"UNKNOWN_KEY\" found.");
    }

    @Test
    @DisplayName("Input field reference key")
    void inputFieldReferenceKey() {
        assertErrorsContain("inputFieldReferenceKey", "No key with name \"UNKNOWN_KEY\" found.");
    }

    @Test
    @DisplayName("Argument reference key")
    void argumentReferenceKey() {
        assertErrorsContain("argumentReferenceKey", "No key with name \"UNKNOWN_KEY\" found.");
    }

    @Test
    @DisplayName("Referencing unknown type")
    void type() {
        assertErrorsContain("type", "Field \"query\" within schema type \"Query\" has invalid type \"INVALID\"");
    }

    @Test
    @DisplayName("Referencing unknown type one close match")
    void typeDistance() {
        assertErrorsContain(
                "typeDistance",
                "Field \"query\" within schema type \"Query\" has invalid type \"Typee\" (or an union containing it). " +
                        "Closest type matches found by levenshtein distance are:\nType - 1"
        );
    }

    @Test
    @DisplayName("Referencing unknown type with two equally close matches")
    void typeDistanceEqual() {
        assertErrorsContain(
                "typeDistanceEqual",
                "Field \"query\" within schema type \"Query\" has invalid type \"Type\" (or an union containing it). " +
                        "Closest type matches found by levenshtein distance are:\nType0 - 1, Type1 - 1"
        );
    }

    @Test
    @DisplayName("Referencing unknown type inside an union")
    void typeInUnion() {
        assertErrorsContain(
                () -> getProcessedSchema("typeInUnion", DUMMY_TYPE),
                "Field \"query\" within schema type \"Query\" has invalid type \"INVALID\""
        );
    }

    @Test
    @DisplayName("Referencing unknown type inside a nested union")
    void typeInNestedUnion() {
        assertErrorsContain(
                () -> getProcessedSchema("typeInNestedUnion", DUMMY_TYPE),
                "Field \"query\" within schema type \"Query\" has invalid type \"INVALID\""
        );
    }

    @Test
    @Disabled("Input types are not checked")
    @DisplayName("Referencing unknown input type")
    void inputType() {
        assertErrorsContain("inputType", "Field \"in\" within schema type \"Query\" has invalid type \"INVALID\"");
    }

    @Test
    @DisplayName("Field that can not be connected to anything in a table")
    void field() {
        assertErrorsContain("field",
                "No field with name 'WRONG' found in table 'CUSTOMER' which is required by 'Customer.wrong'."
        );
    }

    @Test
    @DisplayName("Field that can not be connected to a field in implicit table provided through input")
    void fieldWithImplicitTableFromInput() {
        assertErrorsContain("fieldWithImplicitTableFromInput", Set.of(CUSTOMER_NODE_INPUT_TABLE),
                "No field with name 'WRONG' found in table 'CUSTOMER' which is required by 'Query.query'."
        );
    }

    @Test
    @DisplayName("Wrapped field that can not be connected to a field in implicit table provided through input")
    void wrappedFieldWithImplicitTableFromInput() {
        assertErrorsContain("wrappedFieldWithImplicitTableFromInput", Set.of(CUSTOMER_NODE_INPUT_TABLE),
                "No field with name 'WRONG' found in table 'CUSTOMER' which is required by 'CustomerNoTable.wrong'."
        );
    }

    @Test
    @DisplayName("Mutation with conflicting input and output tables validates output against output table")
    void fieldInMutationWithConflictingTable() {
        assertErrorsContain("fieldInMutationWithConflictingTable", Set.of(CUSTOMER_NODE_INPUT_TABLE),
                "No field with name 'WRONG' found in table 'ADDRESS' which is required by 'Address.wrong'."
        );
    }

    @Test
    @DisplayName("Field that exists in a table")
    void knownField() {
        getProcessedSchema("knownField");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field that can not be connected to anything in a table")
    void argument() {
        assertErrorsContain("argument", Set.of(CUSTOMER_TABLE),
                "No field with name 'WRONG' found in table 'CUSTOMER'"
        );
    }

    @Test
    @DisplayName("Field that can not be connected to anything in a table but is not generated")
    void unknownNotGeneratedField() {
        getProcessedSchema("unknownNotGeneratedField");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field that exists in a table")
    void knownArgument() {
        getProcessedSchema("knownArgument", Set.of(CUSTOMER_TABLE));
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field that can not be connected to anything in a table but is not generated")
    void notGeneratedArgument() {
        getProcessedSchema("notGeneratedArgument", Set.of(CUSTOMER_TABLE));
        assertNoWarnings();
    }

    @Test // ID fields are not validated.
    @DisplayName("ID field that can not be connected to anything in a table")
    void id() {
        getProcessedSchema("id");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Wrapped field that can not be connected to anything in a table")
    void fieldWrapped() {
        assertErrorsContain("fieldWrapped",
                "No field with name 'WRONG' found in table 'CUSTOMER'"
        );
    }

    @Test
    @DisplayName("Input field that can not be connected to a table but covered by an overriding condition")
    void argumentWithOverrideCondition() {
        getProcessedSchema("argumentWithOverrideCondition", CUSTOMER_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field that can not be connected to a table with a non-overriding condition")
    void argumentWithCondition() {
        assertErrorsContain("argumentWithCondition", Set.of(CUSTOMER_TABLE),
                "No field with name 'WRONG' found in table 'CUSTOMER'"
        );
    }

    @Test
    @DisplayName("Input field that can not be connected to a table but covered by a surrounding overriding condition")
    void argumentWithFieldOverrideCondition() {
        getProcessedSchema("argumentWithFieldOverrideCondition", CUSTOMER_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field that can not be connected to a table with a surrounding non-overriding condition")
    void argumentWithFieldCondition() {
        assertErrorsContain("argumentWithFieldCondition", Set.of(CUSTOMER_TABLE),
                "No field with name 'WRONG' found in table 'CUSTOMER'"
        );
    }

    @Test
    @DisplayName("Input type field that can not be connected to a table but covered by a surrounding overriding condition")
    void argumentWithTypeOverrideCondition() {
        getProcessedSchema("argumentWithTypeOverrideCondition", CUSTOMER_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input type field that can not be connected to a table with a surrounding non-overriding condition")
    void argumentWithTypeCondition() {
        assertErrorsContain("argumentWithTypeCondition", Set.of(CUSTOMER_TABLE),
                "No field with name 'WRONG' found in table 'CUSTOMER'"
        );
    }

    @Test
    @DisplayName("Referencing unknown service")
    void service() {
        assertErrorsContain("service", "No service with name 'INVALID' found.");
    }

    @Test
    @DisplayName("Service method that can not be found")
    void undefinedServiceMethod() {
        assertErrorsContain("undefinedServiceMethod", "Service reference with name 'class no.sikt.graphitron.codereferences.services.ResolverMutationService' does not contain a method named 'UNDEFINED'.");
    }

    @Test
    @DisplayName("Argument @field column does not exist in @reference table")
    void argumentReferenceFieldNotInTable() {
        assertErrorsContain("argumentReferenceFieldNotInTable", Set.of(CUSTOMER_TABLE),
                "No field with name 'STORE_ID' found in table 'CATEGORY'"
        );
    }

    @Test
    @DisplayName("Argument @field column does not exist in table resolved via @reference key")
    void argumentReferenceKeyFieldNotInTable() {
        assertErrorsContain("argumentReferenceKeyFieldNotInTable", Set.of(CUSTOMER_TABLE),
                "No field with name 'STORE_ID' found in table 'ADDRESS'"
        );
    }

    @Test
    @DisplayName("Argument @field column does not exist in final table of multi-step @reference path")
    void argumentReferenceMultiPathFieldNotInTable() {
        assertErrorsContain("argumentReferenceMultiPathFieldNotInTable", Set.of(CUSTOMER_TABLE),
                "No field with name 'STORE_ID' found in table 'CITY'"
        );
    }

    @Test
    @DisplayName("Argument @field column in @reference table is not validated when overriding condition is present")
    void argumentReferenceWithOverrideCondition() {
        getProcessedSchema("argumentReferenceWithOverrideCondition", CUSTOMER_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field @field column does not exist in @reference table")
    void inputFieldReferenceFieldNotInTable() {
        assertErrorsContain("inputFieldReferenceFieldNotInTable", Set.of(CUSTOMER_TABLE),
                "No field with name 'STORE_ID' found in table 'CATEGORY'"
        );
    }

    @Test
    @DisplayName("Fields returning wrapper types should not cause errors about missing field/method")
    void wrapperType() {
        getProcessedSchema("wrapperTypeWithTable");
        assertNoWarnings();
    }

    @Test
    @DisplayName("External fields should not cause errors about missing field/method for table")
    void externalField() {
        getProcessedSchema("externalField");
        assertNoWarnings();
    }

    @Test
    @DisplayName("jOOQ record service should not incorrectly validate that Java record input fields exist on customer table")
    void javaRecordInputJooqRecordOutput() {
        getProcessedSchema("javaRecordInputJooqRecordOutput", Set.of(CUSTOMER_TABLE));
        assertNoWarnings();
    }

    @Test
    @DisplayName("Service input type missing @table or @record directive")
    void serviceInputMissingDirective() {
        assertErrorsContain("serviceInputMissingDirective",
                "Input type 'TestInput' is used as an argument on service field 'Query.test', but has neither the @table nor the @record directive.");
    }

    @Test
    @DisplayName("Service input type with @record maps to incompatible class")
    void serviceInputTypeMismatch() {
        assertErrorsContain("serviceInputTypeMismatch",
                "on service field 'Query.test'",
                "has input type 'TestInput' which maps to 'CustomerJavaRecord'",
                "but there is no overload of 'check' that accepts this",
                "check(CustomerRecord)");
    }

    @Test
    @DisplayName("Service input type with @table maps to incompatible class")
    void serviceInputTableTypeMismatch() {
        assertErrorsContain("serviceInputTableTypeMismatch",
                "on service field 'Query.test'",
                "has input type 'TestInput' which maps to 'CustomerRecord'",
                "but there is no overload of 'checkString' that accepts this",
                "checkString(String)");
    }

    @Test
    @DisplayName("Service input type with valid @table directive does not produce false positive")
    void serviceInputValidRecord() {
        getProcessedSchema("serviceInputValidRecord");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Nested @table input with another @table field does not produce false positive")
    void serviceInputNestedTableWithRecord() {
        getProcessedSchema("serviceInputNestedTableWithRecord");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Nested @table input with extra method parameter triggers count mismatch")
    void serviceInputCountMismatch() {
        assertErrorsContain("serviceInputCountMismatch",
                "Service field 'Query.test' maps to 2 method parameter(s)",
                "but there is no overload of 'checkNestedWrongCount' with that parameter count",
                "checkNestedWrongCount(CustomerRecord, AddressRecord, CustomerRecord)");
    }

    @Test
    @DisplayName("Listed @table input with valid service method does not produce false positive")
    void serviceInputListedTable() {
        getProcessedSchema("serviceInputListedTable");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Listed @table input with incompatible service method element type")
    void serviceInputListedTableTypeMismatch() {
        assertErrorsContain("serviceInputListedTableTypeMismatch",
                "on service field 'Query.test'",
                "has input type 'TestInput' which maps to 'List<CustomerRecord>'",
                "but there is no overload of 'checkIncorrect' that accepts this",
                "checkIncorrect(List<String>)");
    }

    @Test
    @DisplayName("Listed input but service method expects non-list parameter")
    void serviceInputListedNonListParam() {
        assertErrorsContain("serviceInputListedNonListParam",
                "on service field 'Query.test'",
                "has input type 'TestInput' which maps to 'List<CustomerRecord>'",
                "but there is no overload of 'check' that accepts this",
                "check(CustomerRecord)");
    }

    @Test
    @DisplayName("Non-listed input but service method expects list parameter")
    void serviceInputNonListedListParam() {
        assertErrorsContain("serviceInputNonListedListParam",
                "on service field 'Query.test'",
                "has input type 'TestInput' which maps to 'CustomerRecord'",
                "but there is no overload of 'checkList' that accepts this",
                "checkList(List<CustomerRecord>)");
    }

    @Test
    @DisplayName("Service field on table type returning Java record should not validate record fields against table")
    void tableObjectWithServiceField() {
        getProcessedSchema("tableObjectWithServiceField", Set.of(CUSTOMER_TABLE));
        assertNoWarnings();
    }

    @Test
    @DisplayName("Service input with ordering, pagination, and context arguments does not produce false positive")
    void serviceInputCountWithAllFeatures() {
        getProcessedSchema("serviceInputCountWithAllFeatures", Set.of(CUSTOMER_TABLE, PAGE_INFO));
        assertNoWarnings();
    }
}
