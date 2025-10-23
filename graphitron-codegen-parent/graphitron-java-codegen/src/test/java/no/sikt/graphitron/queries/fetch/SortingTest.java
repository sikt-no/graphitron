package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.REFERENCE_PG_USER_MAPPING_CONDITION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Sorting - Queries with default ordering")
public class SortingTest extends GeneratorTest {
    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(REFERENCE_PG_USER_MAPPING_CONDITION);
    }

    @Override
    protected String getSubpath() {
        return "queries/fetch/sorting";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
        resultDoesNotContain("noPrimaryKey", "orderFields", ".orderBy(");
    }

    @Test
    @DisplayName("Default sorting on list with splitQuery")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery",
                "orderFields = _a_address_223244161_customer",
                "customer).orderBy(_iv_orderFields"
        );
    }

    @Test
    @DisplayName("No sorting on list with splitQuery when table has no primary key")
    void splitQueryNoPrimaryKey() {
        resultDoesNotContain("splitQueryNoPrimaryKey", "orderFields", ".orderBy(");
    }

    @Test
    @DisplayName("Default sorting on list without splitQuery (multiset)")
    void multiset() {
        var generatedFiles = generateFiles("multiset", Set.of());
        contains(generatedFiles,
                "ctx.select(DSL",
                ".from(_a_address_223244161_customer).orderBy(_a_address_223244161_customer.fields(_a_address_223244161_customer.getPrimaryKey().getFieldsArray()))"
        );
        doesNotContain(generatedFiles, "orderFields");
    }

    @Test
    @DisplayName("No sorting on table in multiset when it has no primary key")
    void multisetNoPrimaryKey() {
        resultDoesNotContain("multisetNoPrimaryKey", "orderFields", "orderBy");
    }

    @Test
    @DisplayName("Sorting on nested lists (nested multisets)")
    void nestedLists() {
        assertGeneratedContentContains("nestedLists",
                "orderFields = _a_city.",
                "(_a_city).orderBy(_iv_orderFields)",
                ".from(_a_city_760939060_address).orderBy(",
                ".from(_a_address_609487378_customer).orderBy("
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
        resultDoesNotContain("lookup", "orderFields", "orderBy");
    }
}
