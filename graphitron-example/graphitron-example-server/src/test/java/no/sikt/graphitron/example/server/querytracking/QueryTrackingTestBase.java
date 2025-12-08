package no.sikt.graphitron.example.server.querytracking;

import io.restassured.http.ContentType;
import no.sikt.graphitron.example.server.QueryCapturingExecuteListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Base class for tests that verify which SQL queries are executed.
 * Provides utilities to execute GraphQL queries and inspect the resulting SQL.
 */
public abstract class QueryTrackingTestBase {

    private static final QueryCapturingExecuteListener LISTENER = QueryCapturingExecuteListener.getInstance();

    @BeforeAll
    static void enableQueryCapturing() {
        QueryCapturingExecuteListener.enable();
    }

    @AfterAll
    static void disableQueryCapturing() {
        QueryCapturingExecuteListener.disable();
    }

    @BeforeEach
    void clearCapturedQueries() {
        LISTENER.clear();
    }

    protected void executeQuery(String query) {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", query))
                .post("graphql")
                .then()
                .statusCode(200);
    }

    /**
     * Returns all SQL queries executed since the last clear.
     */
    protected List<String> getExecutedQueries() {
        return LISTENER.getExecutedQueries();
    }

    /**
     * Returns true if any COUNT query was executed.
     */
    protected boolean hasCountQuery() {
        return LISTENER.hasCountQuery();
    }

    /**
     * Returns the number of COUNT queries executed.
     */
    protected long countCountQueries() {
        return LISTENER.countCountQueries();
    }
}
