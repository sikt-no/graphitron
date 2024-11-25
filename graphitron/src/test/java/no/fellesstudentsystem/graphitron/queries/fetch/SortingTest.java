package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.INDEX;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Sorting - Queries with default ordering")
public class SortingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/sorting";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Default sorting on primary key")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("No sorting when table has no primary key")
    void noPrimaryKey() {
        resultDoesNotContain("noPrimaryKey",
                "orderFields",
                ".orderBy("
        );
    }

    @Test
    @DisplayName("Default sorting on list with splitQuery")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery",
                "orderFields = address_2030472956_customer",
                "_customer).orderBy(orderFields"
        );
    }

    @Test
    @DisplayName("No sorting on list with splitQuery when table has no primary key")
    void splitQueryNoPrimaryKey() {
        resultDoesNotContain("splitQueryNoPrimaryKey",
                "orderFields",
                ".orderBy("
        );
    }

    @Test
    @DisplayName("Default sorting on list without splitQuery (multiset)")
    void multiset() {
        var generatedFiles = generateFiles("multiset", Set.of());
        contains(generatedFiles,
                "ctx.select(DSL",
                ".from(address_2030472956_customer).orderBy(address_2030472956_customer.fields(address_2030472956_customer.getPrimaryKey().getFieldsArray()))"
        );
        doesNotContain(generatedFiles, "orderFields");
    }

    @Test
    @DisplayName("No sorting on table in multiset when it has no primary key")
    void multisetNoPrimaryKey() {
        resultDoesNotContain("multisetNoPrimaryKey",
                "orderFields",
                "orderBy");
    }

    @Test
    @DisplayName("Sorting on nested lists (nested multisets)")
    void nestedLists() {
        assertGeneratedContentContains("nestedLists",
                "orderFields = _city.",
                "(_city).orderBy(orderFields)",
                ".from(city_1887334959_address).orderBy(city_",
                ".from(address_1356285680_customer).orderBy(address_"
        );
    }

    @Test
    @DisplayName("No sorting on non-list types")
    void withoutList() {
        assertGeneratedContentMatches("withoutList");
    }

    @Test
    @DisplayName("No sorting on lookup")
    void lookup() {
        resultDoesNotContain("lookup",
                "orderFields",
                "orderBy");
    }
}
