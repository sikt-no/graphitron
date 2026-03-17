package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphitron.validation.InvalidSchemaException;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Sorting - Queries with custom ordering")
public class SortingDirectiveTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/orderby/";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(ORDER);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    // --- Index-based sorting ---

    @Test
    @DisplayName("Index-based sorting with single enum value")
    void indexBasedSorting() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Index-based sorting with pagination")
    void indexBasedWithPagination() {
        assertGeneratedContentMatches("paginated", CUSTOMER_CONNECTION_ORDER, PAGE_INFO);
    }

    @Test
    @DisplayName("Index-based sorting with multiple enum values")
    void indexBasedMultipleEnumValues() {
        assertGeneratedContentContains(
                "twoFields",
                "case \"STORE\" -> QueryHelper.getSortFields(_a_customer, \"IDX_FK_STORE_ID\",",
                "case \"NAME\" -> QueryHelper.getSortFields(_a_customer, \"IDX_LAST_NAME\","
        );
    }

    @Test
    @DisplayName("Index-based sorting on a composite index")
    void indexBasedCompositeIndex() {
        assertGeneratedContentContains(
                "twoFieldIndex",
                "case \"STORE_ID_FILM_ID\" -> QueryHelper.getSortFields(_a_inventory, \"IDX_STORE_ID_FILM_ID\","
        );
    }

    @Test
    @DisplayName("Deprecated @index directive works as @order(index:)")
    void deprecatedIndexDirective() {
        assertGeneratedContentContains("deprecatedIndex",
                "case \"NAME\" -> QueryHelper.getSortFields(_a_customer, \"IDX_LAST_NAME\",");
    }

    // --- Field-based sorting ---

    @Test
    @DisplayName("Field-based sorting")
    void fieldBasedSorting() {
        assertGeneratedContentContains("fields",
                ".LAST_NAME.sort(");
    }

    @Test
    @DisplayName("Field-based sorting with collation")
    void fieldBasedWithCollation() {
        assertGeneratedContentContains("fieldsWithCollation",
                ".LAST_NAME.collate(\"xdanish_ai\")");
    }

    @Test
    @DisplayName("Field-based sorting with multiple fields and collation")
    void fieldBasedMultipleFieldsWithCollation() {
        assertGeneratedContentContains("multipleFieldsWithCollation",
                ".LAST_NAME.collate(\"xdanish_ai\")",
                ".FIRST_NAME.collate(\"xdanish_ai\")");
    }

    // --- Primary key sorting ---

    @Test
    @DisplayName("Primary key sorting")
    void primaryKeySorting() {
        assertGeneratedContentContains("primaryKey",
                "f.sort(",
                "getPrimaryKey().getFieldsArray()");
    }

    // --- Mixed modes ---

    @Test
    @DisplayName("Mixed sorting modes in single enum")
    void mixedSortingModes() {
        assertGeneratedContentContains("mixed",
                "switch (");
    }

    // --- Edge cases and validation ---

    @Test
    @DisplayName("No ordering generated for non-list field")
    void noOrderingForNonListField() {
        assertGeneratedContentMatches("withoutList");
    }

    @Test
    @DisplayName("Empty default sort fields when table has no primary key")
    void noPrimaryKeyFallback() {
        assertGeneratedContentContains("noPrimaryKey",
                "? new SortField[] {}");
    }

    @Test
    @DisplayName("Non-existent index should fail validation")
    void nonExistentIndex() {
        assertThatThrownBy(() -> generateFiles("wrongIndex", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessage("Table 'CUSTOMER' has no index 'WRONG_INDEX' specified for 'EMAIL'");
    }

    @Test
    @DisplayName("Non-existent field in @order(fields:) should fail validation")
    void nonExistentField() {
        assertThatThrownBy(() -> generateFiles("wrongField", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("has no field");
    }

    @Test
    @DisplayName("Multiple @order modes set simultaneously should fail validation")
    void multipleModesSet() {
        assertThatThrownBy(() -> generateFiles("multipleModes", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("must have exactly one");
    }

    @Test
    @DisplayName("Primary key sorting on table without PK should fail validation")
    void primaryKeyOnTableWithoutPK() {
        assertThatThrownBy(() -> generateFiles("primaryKeyNoPK", Set.of()))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("has no primary key");
    }

    @Test
    @DisplayName("Custom field names for orderBy and direction should work")
    void customFieldNames() {
        assertGeneratedContentContains("customFieldNames",
                ".getMyCustomSortField().toString()",
                ".getMyCustomSortDirection().toString()"
        );
    }
}
