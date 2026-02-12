package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Entity queries - Queries for fetching entity types")
public class EntityTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/entity";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new DBClassGenerator(schema));
    }

    @Test
    @DisplayName("One entity exists")
    void defaultCase() {
        assertGeneratedContentContains(
                "default",
                "ctx, List<Map<String, Object>> _mi_representations,",
                "customerSortFieldsFor_entities(_mi_representations)",
                "For_entities(List<Map<String, Object>> _mi_representations",
                """
                var _mi_representations_filtered = _mi_representations
                                .stream()
                                .filter(Objects::nonNull)
                                .filter(_iv_it -> "Customer".equals(_iv_it.get("__typename")))
                                .toList();
                """,
                ".where(_a_customer.hasIds(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get(\"id\")).toList())"
        );
    }

    @Test
    @DisplayName("No entities defined in schema")
    void noEntities() {
        assertNothingGenerated(Set.of(CUSTOMER_TABLE));
    }

    @Test
    @DisplayName("Entity with two keys")
    void twoKeys() {
        assertGeneratedContentContains(
                "twoKeys",
                "row(_a_customer.getId(),_a_customer.FIRST_NAME)",
                "where(_a_customer.hasIds(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get(\"id\")).toList()))" +
                        ".or(_a_customer.FIRST_NAME.in(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get(\"first\")).toList()))"
        );
    }

    @Test
    @DisplayName("Entity with one compound key")
    void compoundKey() {
        assertGeneratedContentContains(
                "compoundKey",
                """
                .where(
                        DSL.and(
                                _a_customer.hasIds(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get("id")).toList()),
                                _a_customer.FIRST_NAME.in(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get("first")).toList())
                        )
                )
                """
        );
    }

    @Test
    @DisplayName("Entity query with integer key")
    void nonStringKey() {
        assertGeneratedContentContains("nonStringKey", "P_FILM_COUNT.in(_mi_representations_filtered.stream().map(_iv_it -> (Integer) _iv_it.get(\"p_film_count\"))");
    }
}
