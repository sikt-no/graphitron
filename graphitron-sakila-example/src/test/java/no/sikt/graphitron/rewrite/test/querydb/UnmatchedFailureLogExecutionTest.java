package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.services.DatabaseErrorService;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage of how an exception no {@code @error} handler claims is logged when it
 * fails many fields at once: one batched child query fails, java-dataloader hands its one
 * exception to every field that was waiting on the batch, and the request answers one error per
 * field. The log gets one record for the failure, and every one of those errors quotes the
 * reference that record carries.
 *
 * <p>Two batched child {@code @service} fields on {@code Film} fail the batch: {@code
 * durabilityError} throws a plain exception, which keeps its stack trace in the record, and
 * {@code databaseError} fails in the database, which is logged as a one-line summary with the
 * exception itself at {@code DEBUG}. Films 1 to 3 are seeded and no test deletes them.
 */
@ExecutionTier
@ResourceLock(ErrorRouterLog.LEVEL_LOCK)
class UnmatchedFailureLogExecutionTest {

    private static final Pattern REFERENCE = Pattern.compile("An error occurred\\. Reference: ([0-9a-f-]{36})\\.");

    static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        String url = SakilaDatabase.url();
        String user = SakilaDatabase.user();
        String password = SakilaDatabase.password();
        dataSource = (DataSource) Proxy.newProxyInstance(
            UnmatchedFailureLogExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> SakilaDatabase.connect(url, user, password);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "unmatchedFailureLogDataSource";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void aBatchThatFailsThreeFields_isThreeErrors_oneReference_andOneRecordWithItsStackTrace() {
        try (var log = ErrorRouterLog.capture()) {
            var result = run(runtime(), "{ filmById(film_id: [\"1\", \"2\", \"3\"]) { durabilityError } }");

            assertThat(result.getErrors()).hasSize(3);
            assertThat(result.getErrors()).extracting(error -> error.getPath().get(2)).containsOnly("durabilityError");
            String reference = theOneReference(result.getErrors());

            assertThat(log.mentioning(reference)).as("every record about the failure").hasSize(1);
            assertThat(log.errorsMentioning(reference)).singleElement().satisfies(record -> {
                assertThat(record.getMessage()).startsWith("Unmatched exception in fetcher; correlation id = ");
                // The plain exception keeps its stack trace: the record carries the throwable as the
                // logging field caught it, the batch's exception inside that field's own wrapper.
                assertThat(record.getThrown()).isInstanceOf(CompletionException.class)
                    .hasRootCauseMessage("durability-test fixture: synthetic mid-traversal error");
            });
        }
    }

    @Test
    void twoBatchesThatFail_areTwoRecords_eachQuotedByItsOwnFields() {
        try (var log = ErrorRouterLog.capture()) {
            var result = run(runtime(), """
                {
                    a: filmById(film_id: ["1", "2"]) { durabilityError }
                    b: filmById(film_id: ["1", "2"]) { durabilityError }
                }
                """);

            assertThat(result.getErrors()).hasSize(4);
            String referenceOfA = theOneReference(errorsUnder(result, "a"));
            String referenceOfB = theOneReference(errorsUnder(result, "b"));

            assertThat(referenceOfA).isNotEqualTo(referenceOfB);
            assertThat(log.errorsMentioning(referenceOfA)).hasSize(1);
            assertThat(log.errorsMentioning(referenceOfB)).hasSize(1);
        }
    }

    @Test
    void aConfiguredLog_isCalledOnceForTheBatch_withTheReferenceTheErrorsQuote() {
        record Unmatched(Throwable thrown, String reference, String path) {}
        var calls = new CopyOnWriteArrayList<Unmatched>();
        try (var log = ErrorRouterLog.capture()) {
            var result = run(
                runtime().withFetcherErrorLog((thrown, reference, env) -> calls.add(
                    new Unmatched(thrown, reference, env.getExecutionStepInfo().getPath().toString()))),
                "{ filmById(film_id: [\"1\", \"2\", \"3\"]) { durabilityError } }");

            assertThat(result.getErrors()).hasSize(3);
            String reference = theOneReference(result.getErrors());

            assertThat(calls).singleElement().satisfies(call -> {
                assertThat(call.reference()).isEqualTo(reference);
                assertThat(call.thrown()).hasRootCauseMessage("durability-test fixture: synthetic mid-traversal error");
                assertThat(call.path()).matches("/filmById\\[[0-2]]/durabilityError");
            });
            assertThat(log.mentioning(reference)).as("the configured log replaces Graphitron's line").isEmpty();
        }
    }

    @Test
    void aBatchThatFailsInTheDatabase_isOneSummaryLineAtError_andTheExceptionAtDebug() {
        try (var log = ErrorRouterLog.captureWithDebug()) {
            var result = run(runtime(), "{ filmById(film_id: [\"1\", \"2\", \"3\"]) { databaseError } }");

            assertThat(result.getErrors()).hasSize(3);
            String reference = theOneReference(result.getErrors());

            assertThat(log.errorsMentioning(reference)).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).as("no stack trace, so no SQL, at ERROR").isNull();
                assertThat(record.getMessage())
                    .startsWith("Unmatched exception in fetcher; correlation id = " + reference + "; path = /filmById[")
                    .containsPattern("path = /filmById\\[[0-2]]/databaseError; ")
                    // jOOQ throws its DataException subclass for a class-22 SQL state.
                    .contains("org.jooq.exception.DataException: org.postgresql.util.PSQLException [22P02/0] ")
                    .contains("invalid input syntax for type json")
                    // Neither the statement nor what PostgreSQL quotes back on its Where: line.
                    .doesNotContainIgnoringCase("select")
                    .doesNotContain("cast(")
                    .doesNotContain("database-error-marker")
                    .doesNotContain("\n");
            });
            assertThat(log.debugsMentioning(reference)).singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Unmatched exception in fetcher; correlation id = " + reference);
                assertThat(messagesOnChain(record.getThrown()))
                    .as("the full exception, statement and driver detail included")
                    .anySatisfy(message -> assertThat(message).contains("cast(").contains(DatabaseErrorService.MARKER));
            });
            assertThat(log.mentioning(reference)).as("every record about the failure").hasSize(2);
        }
    }

    // ===== helpers =====

    private static GraphitronRuntime runtime() {
        return Graphitron.runtime(dataSource, SQLDialect.POSTGRES);
    }

    private static ExecutionResult run(GraphitronRuntime runtime, String query) {
        var engine = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
        var input = Graphitron.newOwnedExecutionInput(
            no.sikt.graphitron.sakila.example.app.SakilaSessions.of("{\"sub\":\"test-user\"}"),
            "{\"sub\":\"test-user\"}", "test-user").query(query);
        return engine.execute(input.build());
    }

    private static List<GraphQLError> errorsUnder(ExecutionResult result, String rootAlias) {
        return result.getErrors().stream().filter(error -> rootAlias.equals(error.getPath().get(0))).toList();
    }

    /** The reference {@code errors} quote, which is one: every error of one failure quotes the same. */
    private static String theOneReference(List<GraphQLError> errors) {
        assertThat(errors).isNotEmpty();
        var references = errors.stream().map(error -> {
            Matcher matcher = REFERENCE.matcher(error.getMessage());
            assertThat(matcher.matches()).as("a redacted error: %s", error.getMessage()).isTrue();
            return matcher.group(1);
        }).distinct().toList();
        assertThat(references).as("the references %d errors quote", errors.size()).hasSize(1);
        return references.get(0);
    }

    private static List<String> messagesOnChain(Throwable thrown) {
        var messages = new ArrayList<String>();
        for (Throwable t = thrown; t != null && messages.size() < 16; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return messages;
    }
}
