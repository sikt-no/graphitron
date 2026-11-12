package no.sikt.graphitron.rewrite.test.internal;

import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of the generated {@link ErrorRouter#describe}: the one-line summary a database
 * failure's default log line carries in place of the exception. Called with hand-built
 * throwables, in memory, since no database produces an overlong first line or a cyclic cause
 * chain on demand.
 *
 * <p>The contract: a cause chain holding a {@link SQLException} gives
 * {@code path = <path>; <failure class>: <driver class> [<SQL state>/<vendor code>] <first line>},
 * the failure being the exception without its async wrappers and the first line that of the
 * driver's message, cut at 240 characters; any other chain gives nothing.
 */
@UnitTier
class ErrorRouterDescribeTest {

    private static final String STATEMENT = "select \"film\".\"film_id\" from \"film\" where \"film\".\"film_id\" in ("
        + "?, ".repeat(400) + "?)";

    private static final String FIRST_LINE = "ERROR: duplicate key value violates unique constraint ";

    private static DataAccessException uniqueViolation(String driverMessage) {
        var driver = new SQLException(driverMessage, "23505", 1);
        return new DataAccessException("SQL [" + STATEMENT + "]; " + driverMessage, driver);
    }

    @Test
    void aDatabaseFailure_isItsPathClassesStateCodeAndTheDriversFirstLine() {
        var thrown = uniqueViolation(FIRST_LINE + "\"customer_email_key\"\n  Detail: Key (email)=(a@b.no) already exists.");

        assertThat(ErrorRouter.describe(thrown, environment())).contains(
            "path = /film; org.jooq.exception.DataAccessException: java.sql.SQLException [23505/1] "
                + FIRST_LINE + "\"customer_email_key\"");
    }

    @Test
    void theStatementAndTheDriversLaterLines_areLeftOut() {
        var thrown = uniqueViolation("\n" + FIRST_LINE + "\"customer_email_key\"\r\n  Detail: Key (email)=(a@b.no) already exists.");

        assertThat(ErrorRouter.describe(thrown, environment())).hasValueSatisfying(summary -> assertThat(summary)
            .contains(FIRST_LINE)
            .doesNotContain("select")
            .doesNotContain("?, ?")
            .doesNotContain("Detail")
            .doesNotContain("a@b.no")
            .doesNotContain("\n")
            .doesNotContain("\r"));
    }

    @Test
    void anOverlongFirstLine_isCutAtTheBound() {
        String overlong = FIRST_LINE + "x".repeat(500);
        var thrown = uniqueViolation(overlong + "\n  Detail: Key (email)=(a@b.no) already exists.");

        assertThat(ErrorRouter.describe(thrown, environment())).hasValueSatisfying(summary -> assertThat(summary)
            .endsWith("[23505/1] " + overlong.substring(0, 240) + "...")
            .doesNotContain("a@b.no"));
    }

    @Test
    void theAsyncWrappersAFieldCaughtItIn_areNotTheFailure() {
        var failure = uniqueViolation(FIRST_LINE + "\"customer_email_key\"");
        var thrown = new CompletionException(new ExecutionException(failure));

        assertThat(ErrorRouter.describe(thrown, environment())).hasValueSatisfying(summary -> assertThat(summary)
            .startsWith("path = /film; org.jooq.exception.DataAccessException: java.sql.SQLException [23505/1] "));
    }

    @Test
    void withoutAnEnvironment_thePathIsUnknown() {
        var thrown = uniqueViolation(FIRST_LINE + "\"customer_email_key\"");

        assertThat(ErrorRouter.describe(thrown, null)).hasValueSatisfying(summary -> assertThat(summary)
            .startsWith("path = unknown; org.jooq.exception.DataAccessException: "));
    }

    @Test
    void aDriverExceptionWithoutMessageOrState_stillDescribes() {
        var thrown = new DataAccessException("SQL [" + STATEMENT + "]", new SQLException());

        assertThat(ErrorRouter.describe(thrown, environment())).contains(
            "path = /film; org.jooq.exception.DataAccessException: java.sql.SQLException [null/0] ");
    }

    @Test
    void aChainWithoutASqlException_describesNothing() {
        var thrown = new CompletionException(new IllegalStateException("a bug in a service", new RuntimeException("cause")));

        assertThat(ErrorRouter.describe(thrown, environment())).isEmpty();
    }

    @Test
    void aCyclicCauseChain_terminates() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(ErrorRouter.describe(first, environment())).isEmpty();

        // The same for a cycle made of nothing but the wrappers the failure is found behind.
        var outer = new CompletionException() {};
        var inner = new CompletionException(outer);
        outer.initCause(inner);
        assertThat(ErrorRouter.describe(outer, environment())).isEmpty();
    }

    private static DataFetchingEnvironment environment() {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("film"))
                .build())
            .build();
    }
}
