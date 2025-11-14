package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch query conditions - External conditions for queries")
public class ConditionTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "queries/fetch/conditions";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(
                QUERY_FETCH_CONDITION,
                QUERY_FETCH_STAFF_CONDITION,
                QUERY_FETCH_ADDRESS_INTERFACE_CONDITION,
                CONTEXT_CONDITION
        );
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema), new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Condition on a parameter")
    void onParam() {
        assertGeneratedContentContains(
                "onParam",
                ".where(_a_customer.EMAIL.eq(_mi_email))" +
                        ".and(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.email(_a_customer, _mi_email)).fetch"
        );
    }

    @Test
    @DisplayName("Overriding condition on a parameter")
    void onParamOverride() {
        assertGeneratedContentContains(
                "onParamOverride",
                ".where(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.email(_a_customer, _mi_email)).fetch"
        );
    }

    @Test
    @DisplayName("Condition on a parameter and one unrelated field")
    void onParamWithOtherField() {
        assertGeneratedContentContains(
                "onParamWithOtherField",
                "customer.EMAIL.eq(_mi_email",
                "customer.FIRST_NAME.eq(_mi_name",
                ".email(_a_customer, _mi_email)"
        );
    }

    @Test
    @DisplayName("Condition on a listed parameter")
    void onListedParam() {
        assertGeneratedContentContains("onListedParam", ".emails(_a_customer, _mi_emails)).fetch");
    }

    @Test
    @DisplayName("Referencing other method name than default assumption")
    void withMethodNameOverride() {
        assertGeneratedContentContains("withMethodNameOverride", ".id(_a_customer, _mi_someID)");
    }

    @Test
    @DisplayName("Condition on a single field")
    void onField() {
        assertGeneratedContentContains("onField", "customer.EMAIL.eq(_mi_email", ".query(_a_customer, _mi_email)");
    }

    @Test
    @DisplayName("Condition on a field with multiple parameters")
    void onFieldMultipleParams() {
        assertGeneratedContentContains("onFieldMultipleParams", ".query(_a_customer, _mi_email, _mi_name)");
    }

    @Test
    @DisplayName("Overriding condition on a field")
    void onFieldOverride() {
        assertGeneratedContentContains(
                "onFieldOverride",
                ".where(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.query(_a_customer, _mi_email)).fetch"
        );
    }

    @Test
    @DisplayName("Conditions on a field and an associated parameter")
    void onParamAndField() {
        assertGeneratedContentContains(
                "onParamAndField",
                "customer.EMAIL.eq(_mi_email",
                ".email(_a_customer, _mi_email)",
                "customer.FIRST_NAME.eq(_mi_name",
                ".query(_a_customer, _mi_email, _mi_name)"
        );
    }

    @Test
    @DisplayName("Condition on a field with override and condition on an associated parameter")
    void onParamAndOverrideField() {
        assertGeneratedContentContains(
                "onParamAndOverrideField",
                ".where(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.email(_a_customer, _mi_email))" +
                        ".and(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.query(_a_customer, _mi_email, _mi_name)).fetch"
        );
    }

    @Test
    @DisplayName("Overriding conditions on a field and an associated parameter")
    void onParamAndFieldOverrideBoth() {
        assertGeneratedContentContains(
                "onParamAndOverrideField",
                ".where(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.email(_a_customer, _mi_email))" +
                        ".and(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.query(_a_customer, _mi_email, _mi_name)).fetch"
        );
    }

    @Test
    @DisplayName("Condition on an input type parameter")
    void onInputParam() {
        assertGeneratedContentContains(
                "onInputParam", Set.of(STAFF, NAME_INPUT),
                "staff.FIRST_NAME.eq(_mi_name.getFirstname())",
                "staff.LAST_NAME.eq(_mi_name.getLastname())",
                ".name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname())"
        );
    }

    @Test
    @DisplayName("Overriding condition on an input type parameter")
    void onInputParamOverride() {
        assertGeneratedContentContains(
                "onInputParamOverride", Set.of(STAFF, NAME_INPUT),
                ".where(_mi_active != null ? _a_staff.ACTIVE.eq(_mi_active) : DSL.noCondition())" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Conditions on parameters of input and scalar type")
    void onInputAndScalarParams() {
        assertGeneratedContentContains(
                "onInputAndScalarParams", Set.of(STAFF, NAME_INPUT),
                "staff.FIRST_NAME.eq(_mi_name.getFirstname())",
                "staff.LAST_NAME.eq(_mi_name.getLastname())",
                "email != null ? _a_staff.EMAIL.eq(_mi_email) : DSL.noCondition()",
                ".email(_a_staff, _mi_email)",
                "staff.ACTIVE.eq(_mi_active)",
                ".name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname())"
        );
    }

    @Test
    @DisplayName("Overriding condition on parameter of input type and condition on scalar type")
    public void onInputOverrideAndScalarParams() {
        assertGeneratedContentContains("onInputOverrideAndScalarParams", Set.of(STAFF, NAME_INPUT),
                ".where(_a_staff.EMAIL.eq(_mi_email))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.email(_a_staff, _mi_email))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition on field where parameter has input type")
    public void onFieldOverrideInputParam() {
        assertGeneratedContentContains(
                "onFieldOverrideInputParam", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.field(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname(), _mi_active))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on both parameter of input type and field")
    public void onInputParamAndField() {
        assertGeneratedContentContains(
                "onInputParamAndField", Set.of(STAFF, NAME_INPUT),
                "staff.FIRST_NAME.eq(_mi_name.getFirstname())",
                "staff.LAST_NAME.eq(_mi_name.getLastname())",
                "active != null ? _a_staff.ACTIVE.eq(_mi_active) : DSL.noCondition())",
                ".field(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname(), _mi_active)",
                ".name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname())"
        );
    }

    @Test
    @DisplayName("Condition on parameter of input type and override condition on field")
    public void onInputParamAndFieldOverride() {
        assertGeneratedContentContains(
                "onInputParamAndFieldOverride", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.field(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname(), _mi_active))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Override condition on both parameter of input type and field")
    public void onInputParamAndFieldOverrideBoth() {
        assertGeneratedContentContains(
                "onInputParamAndFieldOverrideBoth", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.field(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname(), _mi_active))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_name.getFirstname(), _mi_name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on parameter of nested input type")
    public void onNestedInputParam() {
        assertGeneratedContentContains(
                "onNestedInputParam", Set.of(STAFF, NAME_INPUT),
                "staff.FIRST_NAME.eq(_mi_staff.getName().getFirstname())",
                "staff.LAST_NAME.eq(_mi_staff.getName().getLastname())",
                "staff.getActive() != null ? _a_staff.ACTIVE.eq(_mi_staff.getActive()) : DSL.noCondition())",
                ".staffMin(_a_staff, _mi_staff.getName().getFirstname(), _mi_staff.getName().getLastname(), _mi_staff.getActive() != null ? _mi_staff.getActive() : null))"
        );
    }

    @Test
    @DisplayName("Override condition on parameter of nested input type")
    public void onNestedInputOverrideParam() {
        assertGeneratedContentContains(
                "onNestedInputOverrideParam", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.staffMin(_a_staff, _mi_staff.getName().getFirstname(), _mi_staff.getName().getLastname(), _mi_staff.getActive()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on field where parameter has nested input type")
    public void onFieldNestedInputParam() {
        assertGeneratedContentContains(
                "onFieldNestedInputParam", Set.of(STAFF, NAME_INPUT),
                "staff.FIRST_NAME.eq(_mi_staff.getName().getFirstname())",
                "staff.LAST_NAME.eq(_mi_staff.getName().getLastname())",
                "staff.EMAIL.eq(_mi_staff.getEmail())",
                "staff.ACTIVE.eq(_mi_staff.getActive())",
                ".staff(_a_staff, _mi_staff.getName().getFirstname(), _mi_staff.getName().getLastname(), _mi_staff.getEmail(), _mi_staff.getActive())"
        );
    }

    @Test
    @DisplayName("Overriding condition on field where parameter has nested input type")
    public void onFieldOverrideNestedInputParam() {
        assertGeneratedContentContains(
                "onFieldOverrideNestedInputParam", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.staff(_a_staff, _mi_staff.getName().getFirstname(), _mi_staff.getName().getLastname(), _mi_staff.getEmail(), _mi_staff.getActive()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition and condition on parameters of input types on same level within a multi-level input type")
    public void onMultiLevelInputDifferentOverride() {
        assertGeneratedContentContains(
                "onMultiLevelInputDifferentOverride", Set.of(STAFF, NAME_INPUT),
                ".where(_a_staff.EMAIL.eq(_mi_staff.getInfo().getJobEmail().getEmail()))" +
                ".and(_a_staff.ACTIVE.eq(_mi_staff.getActive()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_staff.getInfo().getName().getFirstname(), _mi_staff.getInfo().getName().getLastname()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.email(_a_staff, _mi_staff.getInfo().getJobEmail().getEmail()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition and condition on parameters of input types on same level within a multi-level input and override condition on field")
    public void onMultiLevelInputAndFieldDifferentOverride() {
        assertGeneratedContentContains(
                "onMultiLevelInputAndFieldDifferentOverride", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.staff(_a_staff, _mi_staff.getInfo().getName().getFirstname(), _mi_staff.getInfo().getName().getLastname(), _mi_staff.getInfo().getJobEmail().getEmail(), _mi_staff.getActive()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_staff.getInfo().getName().getFirstname(), _mi_staff.getInfo().getName().getLastname()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.email(_a_staff, _mi_staff.getInfo().getJobEmail().getEmail()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on toplevel parameter input type, overriding condition and condition on input types on same level within multi-level input, and condition on scalar values")
    public void onMultiLevelInputAndScalarDifferentOverride() {
        assertGeneratedContentContains(
                "onMultiLevelInputAndScalarDifferentOverride", Set.of(STAFF, NAME_INPUT),
                ".where(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.firstname(_a_staff, _mi_staff.getInfo().getName().getFirstname()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.lastname(_a_staff, _mi_staff.getInfo().getName().getLastname()))" +
                ".and(_a_staff.EMAIL.eq(_mi_staff.getInfo().getJobEmail().getEmail()))" +
                ".and(_a_staff.ACTIVE.eq(_mi_staff.getActive()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.staff(_a_staff, _mi_staff.getInfo().getName().getFirstname(), _mi_staff.getInfo().getName().getLastname(), _mi_staff.getInfo().getJobEmail().getEmail(), _mi_staff.getActive()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.name(_a_staff, _mi_staff.getInfo().getName().getFirstname(), _mi_staff.getInfo().getName().getLastname()))" +
                ".and(no.sikt.graphitron.codereferences.conditions.QueryStaffCondition.email(_a_staff, _mi_staff.getInfo().getJobEmail().getEmail()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on a field with an input type parameter")
    void withInput() {
        assertGeneratedContentContains("withInput", Set.of(DUMMY_INPUT), ".query(_a_customer, _mi_in.getId() != null ? _mi_in.getId() : null)");
    }

    @Test
    @Disabled("Does not unwrap listed fields.")
    @DisplayName("Condition on a field with a listed input type parameter")
    void withListedInput() {
        assertGeneratedContentContains("withListedInput", ".query(_a_customer, input.stream().map(_iv_it -> _iv_it.getId()).toList())");
    }

    @Test
    @DisplayName("Condition on a field inside an input type")
    void onFieldInInput() {
        assertGeneratedContentContains("onFieldInInput", ".id(_a_customer, _mi_in.getId())");
    }

    @Test
    @DisplayName("Condition on an enum parameter")
    void onEnum() {
        assertGeneratedContentContains(
                "onEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".enumInput(_a_customer, QueryHelper.makeEnumMap(_mi_enumInput,"
        );
    }

    @Test // Test that the mapping to the method is correct here, but enums generally are tested elsewhere, so the tests just use jOOQ enums otherwise.
    @DisplayName("Condition on a schema-only enum parameter")
    void onStringEnum() {
        assertGeneratedContentContains(
                "onStringEnum", Set.of(DUMMY_ENUM),
                ".email(_a_customer, QueryHelper.makeEnumMap(_mi_enumInput,"
        );
    }

    @Test
    @DisplayName("Condition on a listed enum parameter")
    void onListedEnum() {
        assertGeneratedContentContains(
                "onListedEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".enumInputList(_a_customer, QueryHelper.makeEnumMap(_mi_enumInputList,"
        );
    }

    @Test
    @DisplayName("Condition on a field with an enum type parameter")
    void onParamWithEnum() {
        assertGeneratedContentContains(
                "onParamWithEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".queryEnum(_a_customer, QueryHelper.makeEnumMap(_mi_enumInput,"
        );
    }

    @Test
    @DisplayName("Condition on a field with a listed enum type parameter")
    void onParamWithListedEnum() {
        assertGeneratedContentContains(
                "onParamWithListedEnum", Set.of(DUMMY_ENUM_CONVERTED),
                "queryEnumList(_a_customer, QueryHelper.makeEnumMap(_mi_enumInputList,"
        );
    }

    @Test
    @DisplayName("Condition on a field with an enum type parameter inside an input type")
    void onParamWithEnumInInput() {
        assertGeneratedContentContains(
                "onParamWithEnumInInput", Set.of(DUMMY_ENUM_CONVERTED),
                ".queryEnum(_a_customer, QueryHelper.makeEnumMap(_mi_in.getE(),"
        );
    }

    @Test
    @DisplayName("Condition on field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onFieldReturningSingleTableInterface",
                ".and(_a_address.POSTAL_CODE.eq(_mi_filter.getPostalCode()",
                ".and(no.sikt.graphitron.codereferences.conditions.QueryAddressInterfaceCondition.address(_a_address, _mi_filter.getPostalCode()"
        );
    }

    @Test
    @DisplayName("Condition on a capitalised parameter")
    void onParamWithWrongCapitalisation() {
        assertGeneratedContentContains(
                "onParamWithWrongCapitalisation",
                ", String _mi_sTRING,",
                ".where(_a_customer.EMAIL.eq(_mi_sTRING))" +
                        ".and(no.sikt.graphitron.codereferences.conditions.QueryCustomerCondition.email(_a_customer, _mi_sTRING)).fetch"
        );
    }

    @Test
    @DisplayName("Condition on a capitalised input type parameter")
    void onInputParamWithWrongCapitalisation() {
        assertGeneratedContentContains(
                "onInputParamWithWrongCapitalisation", Set.of(STAFF, NAME_INPUT),
                ", NameInput _mi_nAME,",
                "staff.FIRST_NAME.eq(_mi_nAME.getFirstname())",
                "staff.LAST_NAME.eq(_mi_nAME.getLastname())",
                ".name(_a_staff, _mi_nAME.getFirstname(), _mi_nAME.getLastname())"
        );
    }

    @Test
    @DisplayName("Condition referencing a context field on a parameter")
    void onParamWithContextField() {
        assertGeneratedContentContains(
                "onParamWithContextField",
                "email, String _cf_ctxField, SelectionSet",
                "ContextCondition.email(_a_customer, _mi_email, _cf_ctxField)"
        );
    }

    @Test
    @DisplayName("Condition referencing a context field on a field")
    void onFieldWithContextField() {
        assertGeneratedContentContains(
                "onFieldWithContextField",
                "email, String _cf_ctxField, SelectionSet",
                "ContextCondition.query(_a_customer, _mi_email, _cf_ctxField)"
        );
    }

    @Test
    @DisplayName("Condition referencing a context field on a field within an input type")
    void onInputParamWithContextField() {
        assertGeneratedContentContains(
                "onInputParamWithContextField",
                "In _mi_in, String _cf_ctxField, SelectionSet",
                "ContextCondition.email(_a_customer, _mi_in != null ? _mi_in.getEmail() : null, _cf_ctxField)"
        );
    }

    @Test
    @DisplayName("Condition referencing two context fields on a parameter")
    void onParamWithMultipleContextFields() {
        assertGeneratedContentContains(
                "onParamWithMultipleContextFields",
                "email, String _cf_ctxField1, String _cf_ctxField2, SelectionSet",
                "ContextCondition.email(_a_customer, _mi_email, _cf_ctxField1, _cf_ctxField2)"
        );
    }

    @Test
    @DisplayName("Two conditions each referencing context fields on parameters")
    void onMultipleParamsWithContextFields() {
        assertGeneratedContentContains(
                "onMultipleParamsWithContextFields",
                "email2, String _cf_ctxField1, String _cf_ctxField2, SelectionSet",
                "ContextCondition.email(_a_customer, _mi_email1, _cf_ctxField1)",
                "ContextCondition.email(_a_customer, _mi_email2, _cf_ctxField2)"
        );
    }

    @Test
    @DisplayName("Two conditions each referencing the same context field on parameters")
    void onMultipleParamsWithSameContextField() {
        assertGeneratedContentContains(
                "onMultipleParamsWithSameContextField",
                "email2, String _cf_ctxField, SelectionSet",
                "ContextCondition.email(_a_customer, _mi_email1, _cf_ctxField)",
                "ContextCondition.email(_a_customer, _mi_email2, _cf_ctxField)"
        );
    }
}
