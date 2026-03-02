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

import no.sikt.graphql.directives.GenerationDirective;

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

    @Test
    @DisplayName("One sorting parameter")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Including pagination")
    void paginated() {
        assertGeneratedContentMatches("paginated", CUSTOMER_CONNECTION_ORDER, PAGE_INFO);
    }

    @Test
    @DisplayName("No list or pagination") // Does not do any ordering.
    void withoutList() {
        assertGeneratedContentMatches("withoutList");
    }

    @Test
    @DisplayName("Multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                "case \"STORE\" -> QueryHelper.getSortFields(_a_customer, \"IDX_FK_STORE_ID\",",
                "case \"NAME\" -> QueryHelper.getSortFields(_a_customer, \"IDX_LAST_NAME\","
        );
    }

    @Test
    @DisplayName("Sorting parameter on a two field index")
    void twoFieldIndex() {
        assertGeneratedContentContains(
                "twoFieldIndex",
                "case \"STORE_ID_FILM_ID\" -> QueryHelper.getSortFields(_a_inventory, \"IDX_STORE_ID_FILM_ID\","
        );
    }

    @Test
    @DisplayName("Table without primary key")
    void noPrimaryKey() {
        assertGeneratedContentContains("noPrimaryKey",
                "? new SortField[] {}");
    }

    @Test
    @DisplayName("Sorting on a parameter that has an invalid index")
    void wrongIndex() {
        assertThatThrownBy(() -> generateFiles("wrongIndex", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessage("Table 'CUSTOMER' has no index 'WRONG_INDEX' necessary for sorting by 'EMAIL'");
    }

    @Test
    @DisplayName("Sorting parameter without @order directive set")
    void missingDirective() {
        assertThatThrownBy(() -> generateFiles("missingDirective", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("Expected enum field 'NAME' of 'OrderByField' to have an '@%s' directive", GenerationDirective.ORDER.getName());
    }

    @Test
    @DisplayName("Field-based sorting")
    void fields() {
        assertGeneratedContentContains("fields",
                ".LAST_NAME.sort(");
    }

    @Test
    @DisplayName("Field-based sorting with collation")
    void fieldsWithCollation() {
        assertGeneratedContentContains("fieldsWithCollation",
                ".LAST_NAME.collate(\"xdanish_ai\")");
    }

    @Test
    @DisplayName("Multiple fields with collation")
    void multipleFieldsWithCollation() {
        assertGeneratedContentContains("multipleFieldsWithCollation",
                ".LAST_NAME.collate(\"xdanish_ai\")",
                ".FIRST_NAME.collate(\"xdanish_ai\")");
    }

    @Test
    @DisplayName("Primary key sorting")
    void primaryKey() {
        assertGeneratedContentContains("primaryKey",
                "f.sort(",
                "getPrimaryKey().getFieldsArray()");
    }

    @Test
    @DisplayName("Mixed sorting modes")
    void mixed() {
        assertGeneratedContentContains("mixed",
                "switch (");
    }

    @Test
    @DisplayName("Non-existent field in @order(fields:)")
    void wrongField() {
        assertThatThrownBy(() -> generateFiles("wrongField", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("has no field");
    }

    @Test
    @DisplayName("Multiple @order modes set simultaneously")
    void multipleModes() {
        assertThatThrownBy(() -> generateFiles("multipleModes", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("must have exactly one");
    }

    @Test
    @DisplayName("Primary key sorting on table without PK")
    void primaryKeyNoPK() {
        assertThatThrownBy(() -> generateFiles("primaryKeyNoPK", Set.of()))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessageContaining("has no primary key");
    }
}
