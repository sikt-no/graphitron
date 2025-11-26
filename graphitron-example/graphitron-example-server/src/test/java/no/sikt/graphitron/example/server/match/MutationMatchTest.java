package no.sikt.graphitron.example.server.match;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("Test basic edit of data from the GraphQL API")
public class MutationMatchTest extends MatchTestBase {

    @Override
    protected Path getFileDirectory() {
        return Paths.get("src", "test", "resources", "match", "mutations");
    }

    @Test
    @DisplayName("Delete single item with single input by node ID")
    public void deleteSingleItemWithSingleInputByNodeId() {
        var categoryId = "Q2F0ZWdvcnk6OTk5OA"; // CATEGORY_ID: 9998

        upsertCategories(List.of(Map.of("categoryId", categoryId, "name", "INSERTED")));

        /*
            Check upsert is successful by querying upserted data
            Note that this is only done for one test here to avoid unnecessary queries
            If upsert fails here, it likely fails for all tests in this class
         */
        queryCategories(List.of(categoryId))
                .rootPath("data")
                .body("categories", hasSize(1));

        // When: The category is deleted
        Map<String, Object> deleteVariables = Map.of("in", Map.of("categoryId", categoryId));
        getValidatableResponse("mutation_delete_category.graphql", deleteVariables)
                .rootPath("data")
                .body("deleteCategory", is(categoryId));

        // Then: The category should no longer exist
        queryCategories(List.of(categoryId))
                .rootPath("data")
                .body("categories", hasSize(0));
    }

    @Test
    @DisplayName("Delete single item with single input by primary key fields")
    public void deleteSingleItemWithSingleInputByPrimaryKey() {
        var categoryId = "Q2F0ZWdvcnk6MTAwNA"; // CATEGORY_ID: 1004

        upsertCategories(List.of(Map.of("categoryId", categoryId, "name", "SHOULD BE DELETED")));

        Map<String, Object> deleteVariables = Map.of("in", Map.of("categoryId", 1004));

        getValidatableResponse("mutation_delete_category_by_pk_and_name.graphql", deleteVariables)
                .rootPath("data")
                .body("deleteCategoryByPkAndName", is(categoryId));

        queryCategories(List.of(categoryId))
                .rootPath("data")
                .body("categories", hasSize(0));
    }

    @Test
    @DisplayName("Delete nothing on no rows matching input conditions")
    public void deleteNothingOnNoMatch() {
        Map<String, Object> deleteVariables = Map.of("in", Map.of("categoryId", 20000)); // Category should not exist

        getValidatableResponse("mutation_delete_category_by_pk_and_name.graphql", deleteVariables)
                .rootPath("data")
                .body("deleteCategoryByPkAndName", nullValue());
    }

    @Test
    @DisplayName("Delete single item with list input by node ID")
    public void deleteSingleItemWithListInputByNodeId() {
        var categoryId = "Q2F0ZWdvcnk6OTk5OQ"; // CATEGORY_ID: 9999

        upsertCategories(List.of(Map.of("categoryId", categoryId, "name", "INSERTED")));

        // When: The category is deleted
        Map<String, Object> deleteVariables = Map.of("in", List.of(Map.of("categoryId", categoryId)));
        getValidatableResponse("mutation_delete_categories.graphql", deleteVariables)
                .rootPath("data")
                .body("deleteCategories[0]", is(categoryId));

        // Then: The category should no longer exist
        queryCategories(List.of(categoryId))
                .rootPath("data")
                .body("categories", hasSize(0));
    }

    @Test
    @DisplayName("Delete multiple items with list input using primary key fields and optional nullable field")
    public void deleteMultipleWithListInputByPrimaryKeyAndOptionalField() {
        var categoryId1 = "Q2F0ZWdvcnk6MTAwMA"; // CATEGORY_ID: 1000
        var categoryId2 = "Q2F0ZWdvcnk6MTAwMQ"; // CATEGORY_ID: 1001
        var categoryId3 = "Q2F0ZWdvcnk6MTAwMg"; // CATEGORY_ID: 1002

        upsertCategories(List.of(
                Map.of("categoryId", categoryId1, "name", "DELETE ME"),
                Map.of("categoryId", categoryId2, "name", "DON'T DELETE ME"),
                Map.of("categoryId", categoryId3, "name", "DELETE ME TOO")));

        Map<String, Object> deleteVariables = Map.of("in",
                List.of(Map.of("categoryId", 1000),
                        Map.of("categoryId", 1001, "name", "WHO?"), // Should not be deleted due to name mismatch
                        Map.of("categoryId", 1002, "name", "DELETE ME TOO"))
        );

        getValidatableResponse("mutation_delete_categories_by_pk_and_name.graphql", deleteVariables)
                .rootPath("data")
                .body("deleteCategoriesByPkAndName", hasSize(2))
                .body("deleteCategoriesByPkAndName[0]", is(categoryId1))
                .body("deleteCategoriesByPkAndName[1]", is(categoryId3));

        queryCategories(List.of(categoryId1, categoryId2, categoryId3))
                .rootPath("data")
                .body("categories", hasSize(1))
                .body("categories[0].id", is(categoryId2));
    }

    @Test
    @DisplayName("Delete operation on data returning wrapped output")
    public void deleteWithWrappedOutput() {
        var categoryId = "Q2F0ZWdvcnk6MTAwMw"; // CATEGORY_ID: 1003

        upsertCategories(List.of(Map.of("categoryId", categoryId, "name", "INSERTED")));

        Map<String, Object> deleteVariables = Map.of("in", List.of(Map.of("categoryId", categoryId)));
        getValidatableResponse("mutation_delete_categories_wrapped.graphql", deleteVariables)
                .rootPath("data.deleteCategoriesWrapped")
                .body("categories", hasSize(1))
                .body("categories[0].categoryId", is(1003))
                .body("categories[0].id", is(categoryId));
    }

    private void upsertCategories(List<Map<String, String>> input) {
        Map<String, Object> upsertVariables = Map.of("in", input);

        getValidatableResponse("mutation_upsert_categories.graphql", upsertVariables)
                .rootPath("data")
                .body("upsertCategories", hasSize(input.size()));
    }

    private ValidatableResponse queryCategories(List<String> categoryIds) {
        var queryFile = "query_categories.graphql";
        Map<String, Object> queryVariables = Map.of("in", categoryIds.stream().map(it -> Map.of("categoryId", it)).toList());
        return getValidatableResponse(queryFile, queryVariables);
    }
}