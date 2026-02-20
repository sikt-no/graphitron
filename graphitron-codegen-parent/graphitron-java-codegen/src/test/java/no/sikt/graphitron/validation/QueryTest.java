package no.sikt.graphitron.validation;

import no.sikt.graphql.directives.GenerationDirective;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static no.sikt.graphql.directives.GenerationDirective.LOOKUP_KEY;
import static no.sikt.graphql.directives.GenerationDirective.ORDER_BY;

@DisplayName("Schema validation - Errors thrown when checking the schema")
public class QueryTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "query";
    }

    @Test
    @DisplayName("Has connection but misses the pagination inputs")
    void noPaginationFields() {
        assertErrorsContain(
                () -> getProcessedSchema("noPaginationFields", CUSTOMER_CONNECTION),
                "Type CustomerConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found."
        );
    }



    @Test
    @DisplayName("Has connection but misses some of the pagination inputs")
    void incompletePaginationFields() {
        assertErrorsContain(
                () -> getProcessedSchema("incompletePaginationFields", CUSTOMER_CONNECTION),
                "Type CustomerConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found."
        );
    }

    @Test
    @DisplayName("Query of Union whose subtype lacks table")
    void unionSubTypeNoTable() {
        assertErrorsContain(
                () -> getProcessedSchema("unionSubTypeNoTable", Set.of(SOMEUNION_CONNECTION, PAGE_INFO)),
                "Type Staff in Union 'SomeUnion' in Query has no table."
        );
    }

    @Test
    @DisplayName("Query with lookup keys set")
    void lookupAndOrderBy() {
        assertErrorsContain(
                () -> getProcessedSchema("lookupAndOrderBy", CUSTOMER_TABLE),
                String.format("'query' has both @%s and @%s defined. These directives can not be used together", ORDER_BY.getName(), LOOKUP_KEY.getName())
        );
    }

    @Test
    @DisplayName("Set both lookup keys and pagination")
    void lookupAndPagination() {
        assertErrorsContain(
                () -> getProcessedSchema("lookupAndPagination", CUSTOMER_CONNECTION),
                String.format("'customers' has both pagination and @%s defined. These can not be used together", GenerationDirective.LOOKUP_KEY.getName())
        );
    }

    @Test  // Reverse references are allowed and should not cause warnings or errors.
    @DisplayName("Correct reverse join")
    void reverseJoin() {
        getProcessedSchema("reverseJoin", Set.of(CUSTOMER_TABLE));
        assertNoWarnings();
    }

    @Test
    @DisplayName("Field cannot have both field and externalField directives")
    void externalFieldAndFieldDirectives() {
        assertErrorsContain("externalFieldAndFieldDirectives", "Field name in type Customer cannot have both the field and externalField directives.");
    }

    @Test
    @DisplayName("External field must have one method associated to it")
    void externalFieldMissingMethod() {
        assertErrorsContain("externalFieldMissingMethod", "No method found for field somethingNotDefined");
    }

    @Test
    @DisplayName("External field cannot be associated with multiple methods, only one")
    void externalFieldDuplicatedMethod() {
        assertErrorsContain("externalFieldDuplicatedMethod", "Multiple methods found for field duplicated");
    }

    @Test
    @DisplayName("External field method needs to have generic return type Field")
    void externalFieldMethodWrongType() {
        assertErrorsContain("externalFieldMethodWrongGenericType", "Return type of method needs to be generic type Field");
    }

    @Test
    @DisplayName("External field method needs to have a generic type parameter that matches scalar type in schema field")
    void externalFieldMethodWrongGenericType() {
        assertErrorsContain("externalFieldMethodWrongGenericTypeParameter", "Type parameter of generic type Field in method needs to match scalar type of field");
    }

    @Test
    @DisplayName("External field in schema needs to have a container type with a table directly associated with it")
    void externalFieldNoTable() {
        assertErrorsContain("externalFieldNoTable", "No table found for field name");
    }

    @Test
    @DisplayName("Subtype should not be validated as reference")
    void sharedSubtypeShouldNotBeValidatedAsReference() {
        getProcessedSchema("sharedSubtypeShouldNotBeValidatedAsReference");
        assertNoWarnings();
    }
    @Test
    @DisplayName("reference directive is needed when there are multiple foreign keys between tables. ")
    void multipleFKToTable() {
        assertErrorsContain("multipleForeignKeysNoRef", "Multiple foreign keys found between tables \"FILM\" and \"LANGUAGE\"");
    }

    @Test
    @DisplayName("Correct reference to table with multiple foreign keys.")
    void multipleFKToTableWithRef() {
        getProcessedSchema("multipleForeignKeysWithRef");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Scalar field with multiple paths not specifying which key to use")
    void scalarFieldWithMultiplePossiblePaths() {
        assertErrorsContain("scalarFieldWithMultiplePossiblePaths", "Multiple foreign keys found between tables \"FILM\" and \"LANGUAGE\"");
    }

    @Test
    @DisplayName("Scalar field specifying target with both key and table")
    void scalarFieldWithMultiplePossiblePathsWithKeyAndTable() {
        getProcessedSchema("scalarFieldWithMultiplePossiblePathsWithKeyAndTable");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Scalar field specifying target with key only")
    void scalarFieldWithMultiplePossiblePathsWithKey() {
        getProcessedSchema("scalarFieldWithMultiplePossiblePathsWithKey");
        assertNoWarnings();
    }

    @Test
    @DisplayName("No foreign key between tables")
    void noForeignKeyToTable() {
        assertErrorsContain("noForeignKey", "No foreign key found between tables");
    }

    @Test
    @DisplayName("Chained reference tags to table")
    void chainedReferenceToTable() {
        getProcessedSchema("chainedReferences");
    }

    @Test
    @DisplayName("Checking  key_reference for self-reference")
    void checkImplicitKeyReference() {
        assertErrorsContain("invalidSelfReference", "No foreign key found between tables \"CITY\" and \"CITY\"");
    }

    @Test
    @DisplayName("Listed, then input nested and listed again field") // Could equivalently be input as well, but field is simpler.
    void listedNestedListedField() {
        assertErrorsContain("listedNestedListedField", Set.of(CUSTOMER_TABLE),
                "Argument 'in0' is a collection of InputFields ('Wrapper') type." +
                        " Fields returning collections: 'in1' are not supported on such types (used for generating condition tuples)"
        );
    }

    @Test
    @DisplayName("Listed optional input field")
    void listedOptionalInput() {
        assertErrorsContain("listedOptionalInput", Set.of(DUMMY_INPUT, CUSTOMER_TABLE),
                "Argument 'in' is a collection of InputFields ('DummyInput') type." +
                        " Optional fields on such types are not supported." +
                        " The following fields will be treated as mandatory in the resulting, generated condition tuple: 'id'"
        );
    }

    @Test
    @DisplayName("Query on root with an interface implementation without table set")
    void interfaceWithoutTableFromRoot() {
        assertErrorsContain("interfaceWithoutTableFromRoot",
                "Interface 'SomeInterface' is returned in field 'someInterface', but " +
                        "type 'Customer' implementing 'SomeInterface' does not have table set. This is not supported."
        );
    }


    @Test
    @DisplayName("Interface with one implementing type validation.")
    void interfaceWithOneSubtype() {
        assertErrorsContain("interfaceWithOneType", "The field someInterface's type SomeInterface has 1 implementing type(s).");
    }

    @Test
    @DisplayName("Union with one implementing type validation.")
    void unionWithOneSubtype() {
        assertErrorsContain("unionWithOneType", "The field someUnion's type SomeUnion has 1 implementing type(s).");
    }

    @Test
    @DisplayName("Union with one error type should not throw error")
    void errorUnionWithOneSubtype() {
        getProcessedSchema("errorUnionWithOneSubtype", Set.of(ERROR));
    }

    @Test
    @DisplayName("Listed wrapper type in table type should throw error")
    void listedWrapperType() {
        assertErrorsContain("listedWrapperType",
                "Field 'Customer.name' returns a list of wrapper type 'CustomerName' (a type wrapping a subset of the table fields), " +
                        "which is not supported. Change the field to return a single 'CustomerName' to fix.");
    }

    @Test
    @DisplayName("Listed wrapper type (with table directive) in table type should throw error")
    void listedWrapperTypeWithTable() {
        assertErrorsContain("listedWrapperTypeWithTable",
                "Field 'Customer.name' returns a list of wrapper type 'CustomerName' (a type wrapping a subset of the table fields), " +
                        "which is not supported. Change the field to return a single 'CustomerName' to fix.");
    }

    @Test
    @DisplayName("Nested listed wrapper type in table type should throw error")
    void nestedListedWrapperType() {
        assertErrorsContain("nestedListedWrapperType",
                "Field 'NameWrapper.name' returns a list of wrapper type 'CustomerName' (a type wrapping a subset of the table fields), " +
                        "which is not supported. Change the field to return a single 'CustomerName' to fix.");
    }

    @Test
    @DisplayName("Validation should not fail for tables with \"_\" suffix in java field name")
    void tableWithSameNameAsSchema() {
        getProcessedSchema("tableWithSameNameAsSchema");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Error should be thrown on lookup argument with @reference")
    void lookupAndReferenceOnArgument() {
        assertErrorsContain("lookupAndReferenceOnArgument",
                "Argument/input field 'customerId' on 'Query.query' has lookupKey directive, but is a reference field. " +
                        "Lookup on references is not currently supported."
        );
    }

    @Test
    @DisplayName("Error should be thrown on lookup input type containing @reference fields")
    void lookupInputTypeWithReferenceFields() {
        assertErrorsContain("lookupInputTypeWithReferenceFields",
                "Argument 'input' on 'Query.query' has lookupKey directive, but contains reference field(s): 'LookupInput.customerId', 'LookupInput.cityId'. " +
                        "Lookup on references is not currently supported."
        );
    }

    @Test
    @DisplayName("No error on lookup input type without reference fields")
    void noWarningsOnLookupTypeWithoutReferenceFields() {
        getProcessedSchema("lookupInputType", Set.of(CUSTOMER_NODE));
        assertNoWarnings();
    }
}
