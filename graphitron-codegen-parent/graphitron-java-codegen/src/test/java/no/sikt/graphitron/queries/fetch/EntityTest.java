package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityOnlyFetchDBClassGenerator;
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
        return List.of(new EntityOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("One entity exists with ID key")
    void defaultCase() {
        assertGeneratedContentContains(
                "default",
                "ctx, Set<Map<String, Object>> _mi_representations,",
                """
                var _mi_representations_filtered = _mi_representations
                                .stream()
                                .filter(Objects::nonNull)
                                .filter(_iv_it -> "Customer".equals(_iv_it.get("__typename")))
                                .toList();
                var _a_customer = CUSTOMER.as("customer_2168032777");
                return _iv_ctx
                        .select(
                                DSL.row(_a_customer.getId()),
                                customerFor_Entity_customer()
                        )
                        .from(_a_customer)
                        .where(_a_customer.hasIds(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get("id")).toList()))
                        .fetchMap(_iv_it -> {
                                    var _iv_rep = new HashMap<String, Object>();
                                    _iv_rep.put("__typename", "Customer");
                                    _iv_rep.put("id", _iv_it.value1().value1());
                                    return _iv_rep;
                                },
                                _iv_it -> (_Entity) _iv_it.value2());
                """
        );
    }

    @Test
    @DisplayName("No entities defined in schema")
    void noEntities() {
        assertNothingGenerated(Set.of(CUSTOMER_TABLE));
    }

    @Test
    @DisplayName("Entity with two keys, one ID and one String")
    void twoKeys() {
        assertGeneratedContentContains(
                "twoKeys",
                "row(_a_customer.getId(),_a_customer.FIRST_NAME)",
                "where(_a_customer.hasIds(_mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get(\"id\")).toList()))" +
                        ".or(_a_customer.FIRST_NAME.in(_mi_representations_filtered.stream().map(_iv_it -> _iv_it.get(\"first\")).toList()))"
                ,
                """
                _iv_rep.put("id", _iv_it.value1().value1());
                _iv_rep.put("first", _iv_it.value1().value2());
                """
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
                                _a_customer.FIRST_NAME.in(_mi_representations_filtered.stream().map(_iv_it -> _iv_it.get("first")).toList())
                        )
                )
                """
                ,
                """
                _iv_rep.put("id", _iv_it.value1().value1());
                _iv_rep.put("first", _iv_it.value1().value2());
                """
        );
    }

    @Test
    @DisplayName("Entity query with integer key")
    void nonStringKey() {
        assertGeneratedContentContains("nonStringKey",
                "P_FILM_COUNT.in(_mi_representations_filtered.stream().map(_iv_it -> _iv_it.get(\"p_film_count\"))"
        );
    }
}
