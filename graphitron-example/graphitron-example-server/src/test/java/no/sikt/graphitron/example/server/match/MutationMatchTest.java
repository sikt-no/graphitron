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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("Test basic edit of data from the GraphQL API")
public class MutationMatchTest extends MatchTestBase {

    @Override
    protected Path getFileDirectory() {
        return Paths.get("src", "test", "resources", "match", "mutations");
    }

    @Test
    @DisplayName("Delete operation on data by node ID can be performed")
    public void deleteByNodeId() {
        var mutationFile = "mutation_upsert_categories.graphql";
        var deleteFile = "mutation_delete_categories.graphql";
        var categoryId = "Q2F0ZWdvcnk6OTk5";

        Map<String, Object> upsertVariables = Map.of("in",
                List.of(Map.of(
                        "categoryId", categoryId,
                        "name", "INSERTED"
                ))
        );

        Map<String, Object> deleteVariables = Map.of("in", List.of(Map.of("categoryId", categoryId)));

        // Given: A category is inserted by upsert and verified
        getValidatableResponse(mutationFile, upsertVariables)
                .rootPath("data.upsertCategories[0]")
                .body("id", is(categoryId))
                .body("name", is("INSERTED"));

        queryCategories(List.of(categoryId))
                .rootPath("data.categories[0]")
                .body("id", is(categoryId))
                .body("name", is("INSERTED"));

        // When: The category is deleted
        getValidatableResponse(deleteFile, deleteVariables)
                .rootPath("data")
                .body("deleteCategories[0]", is(categoryId));

        // Then: The category should no longer exist
        queryCategories(List.of(categoryId))
                .rootPath("data")
                .body("categories", hasSize(0));
    }

    @Test
    @DisplayName("Delete mutation with primary key fields and optional nullable field")
    public void deleteWithPrimaryKeyAndOptionalField() {
        var categoryId1 = "Q2F0ZWdvcnk6MTAwMA"; // CATEGORY_ID: 1000
        var categoryId2 = "Q2F0ZWdvcnk6MTAwMQ"; // CATEGORY_ID: 1001
        var categoryId3 = "Q2F0ZWdvcnk6MTAwMg"; // CATEGORY_ID: 1002
        var categoryIds = List.of(categoryId1, categoryId2, categoryId3);

        Map<String, Object> upsertVariables = Map.of("in",
                List.of(Map.of("categoryId", categoryId1, "name", "DELETE ME"),
                        Map.of("categoryId", categoryId2, "name", "DON'T DELETE ME"),
                        Map.of("categoryId", categoryId3, "name", "DELETE ME TOO"))
        );

        Map<String, Object> deleteVariables = Map.of("in",
                List.of(Map.of("categoryId", 1000),
                        Map.of("categoryId", 1001, "name", "WHO?"), // Should not be deleted due to name mismatch
                        Map.of("categoryId", 1002, "name", "DELETE ME TOO"))
        );

        // Upsert data to delete
        getValidatableResponse("mutation_upsert_categories.graphql", upsertVariables)
                .rootPath("data")
                .body("upsertCategories", hasSize(3))
                .body("upsertCategories.id", contains(categoryId1, categoryId2, categoryId3));

        // Query data to ensure upsert is successful
        queryCategories(categoryIds)
                .rootPath("data")
                .body("categories", hasSize(3));

        // Delete data
        getValidatableResponse("mutation_delete_categories_by_pk_and_name.graphql", deleteVariables)
                .rootPath("data")
                .body("deleteCategoriesByPkAndName", hasSize(2))
                .body("deleteCategoriesByPkAndName[0]", is(categoryId1))
                .body("deleteCategoriesByPkAndName[1]", is(categoryId3));

        // Query data to ensure only two has been deleted
        queryCategories(categoryIds)
                .rootPath("data")
                .body("categories", hasSize(1))
                .body("categories[0].id", is(categoryId2));
    }

    @Test
    @DisplayName("Delete operation on data returning wrapped output")
    public void deleteWithWrappedOutput() {
        var categoryId = "Q2F0ZWdvcnk6MTAwMw"; // CATEGORY_ID: 1003

        Map<String, Object> upsertVariables = Map.of("in",
                List.of(Map.of(
                        "categoryId", categoryId,
                        "name", "INSERTED"
                ))
        );

        Map<String, Object> deleteVariables = Map.of("in", List.of(Map.of("categoryId", categoryId)));

        getValidatableResponse("mutation_upsert_categories.graphql", upsertVariables)
                .rootPath("data.upsertCategories[0]")
                .body("id", is(categoryId))
                .body("name", is("INSERTED"));

        getValidatableResponse("mutation_delete_categories_wrapped.graphql", deleteVariables)
                .rootPath("data.deleteCategoriesWrapped")
                .body("categories", hasSize(1))
                .body("categories[0].categoryId", is(1003))
                .body("categories[0].id", is(categoryId));
    }

    private ValidatableResponse queryCategories(List<String> categoryIds) {
        var queryFile = "query_categories.graphql";
        Map<String, Object> queryVariables = Map.of("in", categoryIds.stream().map(it -> Map.of("categoryId", it)).toList());
        return getValidatableResponse(queryFile, queryVariables);
    }
}