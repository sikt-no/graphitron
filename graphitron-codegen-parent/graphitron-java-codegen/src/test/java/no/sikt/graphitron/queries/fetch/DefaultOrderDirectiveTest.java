package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphitron.validation.InvalidSchemaException;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.ORDER;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultOrder - Queries with default ordering via @defaultOrder directive")
public class DefaultOrderDirectiveTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/defaultOrder/";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Default order with index - uses getSortFields instead of primary key")
    void defaultOrder() {
        assertGeneratedContentContains("default", "getSortFields(_a_customer, \"IDX_LAST_NAME\", \"ASC\")");
    }

    @Test
    @DisplayName("Default order with DESC direction")
    void defaultOrderWithDirection() {
        assertGeneratedContentContains("withDirection", "getSortFields(_a_customer, \"IDX_LAST_NAME\", \"DESC\")");
    }

    @Test
    @DisplayName("Paginated query with default order")
    void paginatedWithDefaultOrder() {
        assertGeneratedContentContains("paginated",
                "_iv_orderFields = QueryHelper.getSortFields(_a_customer, \"IDX_LAST_NAME\", \"ASC\")",
                ".orderBy(_iv_orderFields)",
                "QueryHelper.getOrderByToken(_a_customer, _iv_orderFields)"
        );
    }

    @Test
    @DisplayName("Default order combined with @orderBy - orderBy fallback uses defaultOrder")
    void defaultOrderWithOrderBy() {
        // When orderBy input is null, should use defaultOrder index instead of primary key
        assertGeneratedContentContains("withOrderBy", Set.of(ORDER),
                """
                 var _iv_orderFields = _mi_orderBy == null
                                        ? QueryHelper.getSortFields(_a_customer, "IDX_LAST_NAME", "ASC")
                                        : switch (_mi_orderBy.getOrderByField().toString()) {
                 """
        );
    }

    @Test
    @DisplayName("Invalid index name should fail validation")
    void invalidIndex() {
        assertThatThrownBy(() -> generateFiles("invalidIndex", Set.of()))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("has no index 'NONEXISTENT_INDEX'");
    }

    @Test
    @DisplayName("Default order with fields - uses direct field references")
    void withFields() {
        assertGeneratedContentContains("withFields",
                ".LAST_NAME.sort(SortOrder.DESC)");
    }

    @Test
    @DisplayName("Default order with fields and collation")
    void withFieldsAndCollation() {
        assertGeneratedContentContains("withFieldsAndCollation",
                ".LAST_NAME.collate(\"xdanish_ai\").sort(SortOrder.ASC)");
    }

    @Test
    @DisplayName("Default order with primary key DESC")
    void withPrimaryKey() {
        assertGeneratedContentContains("withPrimaryKey",
                "getPrimaryKey().getFieldsArray()",
                "f.sort(SortOrder.DESC)");
    }

    @Test
    @DisplayName("Default order with both index and fields should fail")
    void withBothIndexAndFields() {
        assertThatThrownBy(() -> generateFiles("withBothIndexAndFields", Set.of()))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("must have exactly one of index, fields, or primaryKey set");
    }
}
