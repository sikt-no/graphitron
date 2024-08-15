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
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Fetch query conditions - External conditions for queries")
public class ConditionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/conditions";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(QUERY_FETCH_CONDITION);
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
                ".enumInput(CUSTOMER, enumInput == null ? null : enumInput.stream().map(itDummyEnumConverted -> Map.of(", // Note, the null check is not necessary here.
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
                ".queryEnum(CUSTOMER, enumInput == null ? null : enumInput.stream().map(itDummyEnumConverted -> Map.of(", // Note, the null check is not necessary here.
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
