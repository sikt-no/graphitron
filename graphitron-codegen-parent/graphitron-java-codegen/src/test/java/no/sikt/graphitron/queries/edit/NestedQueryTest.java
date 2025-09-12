package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Mutation queries - Queries for updating and then fetching data")
public class NestedQueryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test  // Works the same for queries, but there were additional null-pointers for mutations.
    @DisplayName("Nested output without table")
    void nestedOutput() {
        assertGeneratedContentContains("nestedOutput",
                "DSL.multiset(",
                "CustomerTable::new",
                "new Outer(internal_it_)",
                ".fetchOne(it -> it.into(Outer.class))");
    }

    @Test
    @DisplayName("Listed return type on non-root level with split query")
    void nestedOutputSplitQuery() {
        assertGeneratedContentContains(
                "nestedOutputSplitQuery",
                "Map<Row1<Long>, List<CustomerTable>> customersForOuter(",
                "DSL.multiset(",
                "CustomerTable::new",
                ".fetchMap(r -> r.value1().valuesRow(), r -> r.value2().map(Record1::value1));",
                ".fetchOne(it -> it.into(Outer.class))"
        );
    }

}
