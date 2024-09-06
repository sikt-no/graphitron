package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.QUERY_FETCH_CONDITION;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.QUERY_FETCH_STAFF_CONDITION;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_TABLE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_ENUM;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_ENUM_CONVERTED;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_INPUT;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.NAME_INPUT;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.STAFF;

@DisplayName("Fetch query conditions - External conditions for queries")
public class ConditionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/conditions";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(QUERY_FETCH_CONDITION, QUERY_FETCH_STAFF_CONDITION);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Condition on a parameter")
    void onParam() {
        assertGeneratedContentContains(
                "onParam",
                ".where(CUSTOMER.EMAIL.eq(email))" +
                        ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.email(CUSTOMER, email)).fetch"
        );
    }

    @Test
    @DisplayName("Overriding condition on a parameter")
    void onParamOverride() {
        assertGeneratedContentContains(
                "onParamOverride",
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.email(CUSTOMER, email)).fetch"
        );
    }

    @Test
    @DisplayName("Condition on a parameter and one unrelated field")
    void onParamWithOtherField() {
        assertGeneratedContentContains(
                "onParamWithOtherField",
                "CUSTOMER.EMAIL.eq(email",
                "CUSTOMER.FIRST_NAME.eq(name",
                ".email(CUSTOMER, email)"
        );
    }

    @Test
    @DisplayName("Condition on a listed parameter")
    void onListedParam() {
        assertGeneratedContentContains("onListedParam", ".emails(CUSTOMER, emails)).fetch");
    }

    @Test
    @DisplayName("Referencing other method name than default assumption")
    void withMethodNameOverride() {
        assertGeneratedContentContains("withMethodNameOverride", ".id(CUSTOMER, someID)");
    }

    @Test
    @DisplayName("Condition on a single field")
    void onField() {
        assertGeneratedContentContains("onField", "CUSTOMER.EMAIL.eq(email", ".query(CUSTOMER, email)");
    }

    @Test
    @DisplayName("Condition on a field with multiple parameters")
    void onFieldMultipleParams() {
        assertGeneratedContentContains("onFieldMultipleParams", ".query(CUSTOMER, email, name)");
    }

    @Test
    @DisplayName("Overriding condition on a field")
    void onFieldOverride() {
        assertGeneratedContentContains(
                "onFieldOverride",
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.query(CUSTOMER, email)).fetch"
        );
    }

    @Test
    @DisplayName("Conditions on a field and an associated parameter")
    void onParamAndField() {
        assertGeneratedContentContains(
                "onParamAndField",
                "CUSTOMER.EMAIL.eq(email",
                ".email(CUSTOMER, email)",
                "CUSTOMER.FIRST_NAME.eq(name",
                ".query(CUSTOMER, email, name)"
        );
    }

    @Test
    @DisplayName("Condition on a field with override and condition on an associated parameter")
    void onParamAndOverrideField() {
        assertGeneratedContentContains(
                "onParamAndOverrideField",
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.email(CUSTOMER, email))" +
                        ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.query(CUSTOMER, email, name)).fetch"
        );
    }

    @Test
    @DisplayName("Overriding conditions on a field and an associated parameter")
    void onParamAndFieldOverrideBoth() {
        assertGeneratedContentContains(
                "onParamAndOverrideField",
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.email(CUSTOMER, email))" +
                        ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryCustomerCondition.query(CUSTOMER, email, name)).fetch"
        );
    }

    @Test
    @DisplayName("Condition on a parameter of input type")
    void onInputParam() {
        assertGeneratedContentContains(
                "onInputParam", Set.of(STAFF, NAME_INPUT),
                "STAFF.FIRST_NAME.eq(name.getFirstname())",
                "STAFF.LAST_NAME.eq(name.getLastname())",
                ".name(STAFF, name.getFirstname(), name.getLastname())"
        );
    }

    @Test
    @DisplayName("Overriding condition on a parameter of input type")
    void onInputParamOverride() {
        assertGeneratedContentContains(
                "onInputParamOverride", Set.of(STAFF, NAME_INPUT),
                ".where(active != null ? STAFF.ACTIVE.eq(active) : DSL.noCondition())" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, name.getFirstname(), name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Conditions on parameters of input and scalar type")
    void onInputAndScalarParams() {
        assertGeneratedContentContains(
                "onInputAndScalarParams", Set.of(STAFF, NAME_INPUT),
                "STAFF.FIRST_NAME.eq(name.getFirstname())",
                "STAFF.LAST_NAME.eq(name.getLastname())",
                "email != null ? STAFF.EMAIL.eq(email) : DSL.noCondition()",
                ".email(STAFF, email)",
                "STAFF.ACTIVE.eq(active)",
                ".name(STAFF, name.getFirstname(), name.getLastname())"
        );
    }

    @Test
    @DisplayName("Overriding condition on parameter of input type and condition on scalar type")
    public void onInputOverrideAndScalarParams() {
        assertGeneratedContentContains("onInputOverrideAndScalarParams", Set.of(STAFF, NAME_INPUT),
                ".where(STAFF.EMAIL.eq(email))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.email(STAFF, email))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, name.getFirstname(), name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition on field where parameter has input type")
    public void onFieldOverrideInputParam() {
        assertGeneratedContentContains(
                "onFieldOverrideInputParam", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.field(STAFF, name.getFirstname(), name.getLastname(), active))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on both parameter of input type and field")
    public void onInputParamAndField() {
        assertGeneratedContentContains(
                "onInputParamAndField", Set.of(STAFF, NAME_INPUT),
                "STAFF.FIRST_NAME.eq(name.getFirstname())",
                "STAFF.LAST_NAME.eq(name.getLastname())",
                "active != null ? STAFF.ACTIVE.eq(active) : DSL.noCondition())",
                ".field(STAFF, name.getFirstname(), name.getLastname(), active)",
                ".name(STAFF, name.getFirstname(), name.getLastname())"
        );
    }

    @Test
    @DisplayName("Condition on parameter of input type and override condition on field")
    public void onInputParamAndFieldOverride() {
        assertGeneratedContentContains(
                "onInputParamAndFieldOverride", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.field(STAFF, name.getFirstname(), name.getLastname(), active))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, name.getFirstname(), name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Override condition on both parameter of input type and field")
    public void onInputParamAndFieldOverrideBoth() {
        assertGeneratedContentContains(
                "onInputParamAndFieldOverrideBoth", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.field(STAFF, name.getFirstname(), name.getLastname(), active))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, name.getFirstname(), name.getLastname()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on parameter of nested input type")
    public void onNestedInputParam() {
        assertGeneratedContentContains(
                "onNestedInputParam", Set.of(STAFF, NAME_INPUT),
                "STAFF.FIRST_NAME.eq(staff.getName().getFirstname())",
                "STAFF.LAST_NAME.eq(staff.getName().getLastname())",
                "staff.getActive() != null ? STAFF.ACTIVE.eq(staff.getActive()) : DSL.noCondition())",
                ".staffMin(STAFF, staff.getName().getFirstname(), staff.getName().getLastname(), staff.getActive() != null ? staff.getActive() : null))"
        );
    }

    @Test
    @DisplayName("Override condition on parameter of nested input type")
    public void onNestedInputOverrideParam() {
        assertGeneratedContentContains(
                "onNestedInputOverrideParam", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.staffMin(STAFF, staff.getName().getFirstname(), staff.getName().getLastname(), staff.getActive()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on field where parameter has nested input type")
    public void onFieldNestedInputParam() {
        assertGeneratedContentContains(
                "onFieldNestedInputParam", Set.of(STAFF, NAME_INPUT),
                "STAFF.FIRST_NAME.eq(staff.getName().getFirstname())",
                "STAFF.LAST_NAME.eq(staff.getName().getLastname())",
                "STAFF.EMAIL.eq(staff.getEmail())",
                "STAFF.ACTIVE.eq(staff.getActive())",
                ".staff(STAFF, staff.getName().getFirstname(), staff.getName().getLastname(), staff.getEmail(), staff.getActive())"
        );
    }

    @Test
    @DisplayName("Overriding condition on field where parameter has nested input type")
    public void onFieldOverrideNestedInputParam() {
        assertGeneratedContentContains(
                "onFieldOverrideNestedInputParam", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.staff(STAFF, staff.getName().getFirstname(), staff.getName().getLastname(), staff.getEmail(), staff.getActive()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition and condition on parameters of input types on same level within a multi-level input type")
    public void onMultiLevelInputDifferentOverride() {
        assertGeneratedContentContains(
                "onMultiLevelInputDifferentOverride", Set.of(STAFF, NAME_INPUT),
                ".where(STAFF.EMAIL.eq(staff.getInfo().getJobEmail().getEmail()))" +
                ".and(STAFF.ACTIVE.eq(staff.getActive()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.email(STAFF, staff.getInfo().getJobEmail().getEmail()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition and condition on parameters of input types on same level within a multi-level input and override condition on field")
    public void onMultiLevelInputAndFieldDifferentOverride() {
        assertGeneratedContentContains(
                "onMultiLevelInputAndFieldDifferentOverride", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.staff(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname(), staff.getInfo().getJobEmail().getEmail(), staff.getActive() ))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.email(STAFF, staff.getInfo().getJobEmail().getEmail()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on toplevel parameter input type, overriding condition and condition on input types on same level within multi-level input, and condition on scalar values")
    public void onMultiLevelInputAndScalarDifferentOverride() {
        assertGeneratedContentContains(
                "onMultiLevelInputAndScalarDifferentOverride", Set.of(STAFF, NAME_INPUT),
                ".where(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.firstname(STAFF, staff.getInfo().getName().getFirstname()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.lastname(STAFF, staff.getInfo().getName().getLastname()))" +
                ".and(STAFF.EMAIL.eq(staff.getInfo().getJobEmail().getEmail()))" +
                ".and(STAFF.ACTIVE.eq(staff.getActive()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.staff(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname(), staff.getInfo().getJobEmail().getEmail(), staff.getActive()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.name(STAFF, staff.getInfo().getName().getFirstname(), staff.getInfo().getName().getLastname()))" +
                ".and(no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions.QueryStaffCondition.email(STAFF, staff.getInfo().getJobEmail().getEmail()))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Condition on a field with an input type parameter")
    void withInput() {
        assertGeneratedContentContains("withInput", Set.of(DUMMY_INPUT), ".query(CUSTOMER, in.getId() != null ? in.getId() : null)");
    }

    @Test
    @Disabled("Does not unwrap listed fields.")
    @DisplayName("Condition on a field with a listed input type parameter")
    void withListedInput() {
        assertGeneratedContentContains("withListedInput", ".query(CUSTOMER, input.stream().map(it -> it.getId()).collect(Collectors.toList()))");
    }

    @Test
    @DisplayName("Condition on a field inside an input type")
    void onFieldInInput() {
        assertGeneratedContentContains("onFieldInInput", ".id(CUSTOMER, in.getId())");
    }

    @Test
    @DisplayName("Condition on an enum parameter")
    void onEnum() {
        assertGeneratedContentContains(
                "onEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".enumInput(CUSTOMER, enumInput == null ? null : Map.of(", // Note, the null check is not necessary here.
                ").getOrDefault(enumInput, null)"
        );
    }

    @Test // Test that the mapping to the method is correct here, but enums generally are tested elsewhere, so the tests just use jOOQ enums otherwise.
    @DisplayName("Condition on a schema-only enum parameter")
    void onStringEnum() {
        assertGeneratedContentContains(
                "onStringEnum", Set.of(DUMMY_ENUM),
                ".email(CUSTOMER, enumInput == null ? null : Map.of(", // Note, the null check is not necessary here.
                ").getOrDefault(enumInput, null)"
        );
    }

    @Test
    @DisplayName("Condition on a listed enum parameter")
    void onListedEnum() {
        assertGeneratedContentContains(
                "onListedEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".enumInputList(CUSTOMER, enumInputList == null ? null : enumInputList.stream().map(itDummyEnumConverted -> Map.of(", // Note, the null check is not necessary here.
                        ").getOrDefault(itDummyEnumConverted, null)).collect(Collectors.toList()))"
        );
    }

    @Test
    @DisplayName("Condition on a field with an enum type parameter")
    void onParamWithEnum() {
        assertGeneratedContentContains(
                "onParamWithEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".queryEnum(CUSTOMER, enumInput == null ? null : Map.of(", // Note, the null check is not necessary here.
                ").getOrDefault(enumInput, null))"
        );
    }

    @Test
    @DisplayName("Condition on a field with a listed enum type parameter")
    void onParamWithListedEnum() {
        assertGeneratedContentContains(
                "onParamWithListedEnum", Set.of(DUMMY_ENUM_CONVERTED),
                ".queryEnumList(CUSTOMER, enumInputList == null ? null : enumInputList.stream().map(itDummyEnumConverted -> Map.of(", // Note, the null check is not necessary here.
                        ").getOrDefault(itDummyEnumConverted, null)).collect(Collectors.toList())"
        );
    }

    @Test
    @DisplayName("Condition on a field with an enum type parameter inside an input type")
    void onParamWithEnumInInput() {
        assertGeneratedContentContains(
                "onParamWithEnumInInput", Set.of(DUMMY_ENUM_CONVERTED),
                ".queryEnum(CUSTOMER, in.getE() == null ? null : Map.of(", // Note, the null check is not necessary here.
                ").getOrDefault(in.getE(), null))"
        );
    }
}
