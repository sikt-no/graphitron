package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityFetchOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new EntityFetchOnlyDBClassGenerator(schema));
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("One entity exists")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("No entities defined in schema")
    void noEntities() {
        assertNothingGenerated("noEntities");
    }

    @Test
    @DisplayName("Entity queries for two types")
    void twoTypes() {
        assertGeneratedContentContains("twoTypes", "customerAsEntity", "addressAsEntity");
    }

    @Test
    @DisplayName("Entity with two keys")
    void twoKeys() {
        assertGeneratedContentContains(
                "twoKeys",
                "{_customer.getId(),_customer.FIRST_NAME}",
                "makeMap(_r, new String[] {\"id\", \"first\"}))",
                "where(_customer.hasId((String) _inputMap.get(\"id\"))).or(_customer.FIRST_NAME.eq((String) _inputMap.get(\"first\"))"
        );
    }

    @Test
    @DisplayName("Entity with one compound key")
    void compoundKey() {
        assertGeneratedContentContains(
                "compoundKey",
                "{_customer.getId(),_customer.FIRST_NAME}",
                "makeMap(_r, new String[] {\"id\", \"first\"}))",
                "where(DSL.and(List.of(_customer.hasId((String) _inputMap.get(\"id\")),_customer.FIRST_NAME.eq((String) _inputMap.get(\"first\"))))"
        );
    }

    @Test
    @DisplayName("Entity queries with one entity within another")
    void nested() {
        assertGeneratedContentContains(
                "nested",
                "_customer.getId()," +
                        "DSL.field(" +
                        "DSL.select(DSL.row(new Object[]{customer_2952383337_address.getId()}).mapping(Map.class, _r -> QueryHelper.makeMap(_r, new String[] {\"id\"})))" +
                        ".from(customer_2952383337_address)",
                "mapping(Map.class, _r -> QueryHelper.makeMap(_r, new String[] {\"id\", \"address\"}))"
        );
    }

    @Test
    @DisplayName("Entity query with integer key")
    void nonStringKey() {
        assertGeneratedContentContains("nonStringKey", ".P_FILM_COUNT.eq((Integer) _inputMap.get(\"p_film_count\")");
    }
}
