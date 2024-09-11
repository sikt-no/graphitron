package no.fellesstudentsystem.graphitron_newtestorder.validation;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_CONDITION;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_TABLE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_TYPE;

@DisplayName("Schema validation - Unknown and unresolvable values")
public class UnknownTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "unknown";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_CONDITION);
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
        getProcessedSchema("field");
        assertWarningsContain("No field(s) or method(s) with name(s) 'WRONG' found in table 'CUSTOMER'");
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
        getProcessedSchema("argument", Set.of(CUSTOMER_TABLE));
        assertWarningsContain("No field(s) or method(s) with name(s) 'WRONG' found in table 'CUSTOMER'");
    }

    @Test
    @Disabled("Does not ignore fields that are not generated.")
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
        getProcessedSchema("fieldWrapped");
        assertWarningsContain("No field(s) or method(s) with name(s) 'WRONG' found in table 'CUSTOMER'");
    }

    @Test
    @DisplayName("Input field that can not be connected to  a table but covered by an overriding condition")
    void argumentWithOverrideCondition() {
        getProcessedSchema("argumentWithOverrideCondition", CUSTOMER_TABLE);
        assertNoWarnings();
    }

    @Test
    @DisplayName("Input field that can not be connected to a table with a non-overriding condition")
    void argumentWithCondition() {
        getProcessedSchema("argumentWithCondition", CUSTOMER_TABLE);
        assertWarningsContain("No field(s) or method(s) with name(s) 'WRONG' found in table 'CUSTOMER'");
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
        getProcessedSchema("argumentWithFieldCondition", CUSTOMER_TABLE);
        assertWarningsContain("No field(s) or method(s) with name(s) 'WRONG' found in table 'CUSTOMER'");
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
        getProcessedSchema("argumentWithTypeCondition", CUSTOMER_TABLE);
        assertWarningsContain("No field(s) or method(s) with name(s) 'WRONG' found in table 'CUSTOMER'");
    }

    @Test
    @DisplayName("Referencing unknown service")
    void service() {
        assertErrorsContain("service", "No service with name 'INVALID' found.");
    }
}
