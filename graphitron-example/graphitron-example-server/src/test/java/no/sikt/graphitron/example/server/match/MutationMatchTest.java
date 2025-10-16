package no.sikt.graphitron.example.server.match;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Test basic edit of data from the GraphQL API")
public class MutationMatchTest extends MatchTestBase {

    @Override
    protected Path getFileDirectory() {
        return Paths.get("src", "test", "resources", "match", "mutations");
    }

    @Test
    @DisplayName("Delete operation on data can be performed")
    public void delete() {
        var mutationFile = "mutation_upsert_categories.graphql";
        var queryFile = "query_category.graphql";
        var deleteFile = "mutation_delete_categories.graphql";
        var categoryId = "Q2F0ZWdvcnk6OTk5";

        // Given: A category is inserted by upsert and verified
        getValidatableResponse(mutationFile)
                .rootPath("data.upsertCategories[0]")
                .body("id", is(categoryId))
                .body("name", is("INSERTED"));
        getValidatableResponse(queryFile)
                .rootPath("data.category")
                .body("id", is(categoryId))
                .body("name", is("INSERTED"));

        // When: The category is deleted
        getValidatableResponse(deleteFile)
                .rootPath("data")
                .body("deleteCategories[0]", is(categoryId));

        // Then: The category should no longer exist
        getValidatableResponse(queryFile)
                .rootPath("data")
                .body("category", is(nullValue()));
    }
}