package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLNamedType;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.FetcherErrorLog;
import no.sikt.graphitron.generated.schema.FetcherErrorResponse;
import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.rewrite.test.services.FilmLookupNotFoundException;
import no.sikt.graphitron.rewrite.test.services.FilmLookupService;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage of the two consumer hooks a Graphitron 9 error fallback is rebuilt from:
 * {@code FetcherErrorLog.matched}, which logs an exception an {@code @error} handler caught, and
 * {@code FetcherErrorResponse}, which chooses the reference and the client's text. Driven against
 * {@code Query.filmLookup}, whose errors union ends in the catch-all {@code FilmLookupCatchAll}
 * member after {@code FilmLookupGraphQLErrorSource}, a member with no {@code description:}, through the managed runtime (the hooks set by its withers and published by its
 * instrumentation), with one case on the escape-hatch engine. The unmatched cases fail
 * {@code Film.durabilityError}, a batched child service that always throws, on one film.
 *
 * <p>The configures-nothing half of the goal is pinned by the existing {@code filmLookup} and
 * redaction assertions in {@code GraphQLQueryTest} and {@code SessionHookExecutionTest}, which run
 * with neither hook.
 */
@ExecutionTier
class FetcherErrorResponseExecutionTest {

    static DSLContext dsl;
    static String jdbcUrl;
    static String jdbcUser;
    static String jdbcPassword;
    static DataSource dataSource;

    /** The request-context key the tracing response reads its reference from. */
    static final String TRACE_ID = "test.trace-id";

    static final String DEFAULT_REFERENCE = "[0-9a-f-]{36}";

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = SakilaDatabase.url();
        jdbcUser = SakilaDatabase.user();
        jdbcPassword = SakilaDatabase.password();

        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);
        dataSource = (DataSource) Proxy.newProxyInstance(
            FetcherErrorResponseExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> SakilaDatabase.connect(jdbcUrl, jdbcUser, jdbcPassword);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fetcherErrorResponseDataSource";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }


    // ===== the hooks under test =====

    record Matched(Throwable thrown, Throwable claimed, String reference) {}

    record Unmatched(Throwable thrown, String reference) {}

    /** Records every call; overrides {@code matched}, as a log for a catch-all does. */
    static final class RecordingLog implements FetcherErrorLog {
        final List<Matched> matched = new CopyOnWriteArrayList<>();
        final List<Unmatched> unmatched = new CopyOnWriteArrayList<>();

        @Override
        public void unmatched(Throwable thrown, String reference, DataFetchingEnvironment env) {
            unmatched.add(new Unmatched(thrown, reference));
        }

        @Override
        public void matched(Throwable thrown, Throwable claimed, String reference, DataFetchingEnvironment env) {
            matched.add(new Matched(thrown, claimed, reference));
        }
    }

    /**
     * The sis shape: the reference is the request's trace id when it has one; the catch-all
     * member's message quotes it, as does {@code FilmLookupGraphQLErrorSource}'s; the top-level text
     * is Graphitron 9's.
     */
    static class TracingResponse implements FetcherErrorResponse {
        @Override
        public String reference(Throwable thrown, DataFetchingEnvironment env) {
            String traceId = env.getGraphQlContext().get(TRACE_ID);
            return traceId != null ? traceId : FetcherErrorResponse.super.reference(thrown, env);
        }

        @Override
        public String matchedMessage(Throwable source, String defaultMessage, String reference,
                                     DataFetchingEnvironment env) {
            String type = ((GraphQLNamedType) env.getParentType()).getName();
            if (!"FilmLookupCatchAll".equals(type) && !"FilmLookupGraphQLErrorSource".equals(type)) {
                return defaultMessage;
            }
            return defaultMessage + " Referanse: " + reference;
        }

        @Override
        public String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env) {
            return "An exception occurred. The error has been logged with id " + reference + ".";
        }
    }

    private static final String LOOKUP_ERRORS = """
        errors {
            __typename
            ... on FilmLookupNotFound { message }
            ... on FilmLookupInvalid { message }
            ... on FilmLookupGraphQLErrorSource { message }
            ... on FilmLookupCatchAll { message }
        }
        """;

    // ===== FetcherErrorLog.matched =====

    @Test
    void matched_receivesTheCaughtExceptionOnce_andThePayloadIsUnchanged() {
        var log = new RecordingLog();
        var result = run(runtime().withFetcherErrorLog(log), "{ filmLookup(id: 0) { " + LOOKUP_ERRORS + " } }", null);

        assertThat(result.getErrors()).isEmpty();
        assertThat(errorEntry(result, "filmLookup"))
            .containsEntry("__typename", "FilmLookupNotFound")
            .containsEntry("message", "film 0 not found");
        assertThat(log.matched).singleElement().satisfies(m -> {
            assertThat(m.claimed()).isInstanceOf(FilmLookupNotFoundException.class);
            assertThat(m.thrown()).isSameAs(m.claimed());
            assertThat(m.reference()).matches(DEFAULT_REFERENCE);
        });
        assertThat(log.unmatched).isEmpty();
    }

    @Test
    void matched_oneExceptionClaimedByTwoFields_isLoggedOnce_andBothEntriesQuoteOneReference() {
        var log = new RecordingLog();
        var result = run(runtime().withFetcherErrorLog(log).withFetcherErrorResponse(new TracingResponse()),
            "{ a: filmLookup(id: 779) { " + LOOKUP_ERRORS + " } b: filmLookup(id: 779) { " + LOOKUP_ERRORS + " } }",
            null);

        assertThat(result.getErrors()).isEmpty();
        assertThat(log.matched).singleElement().satisfies(m ->
            assertThat(m.claimed()).isSameAs(FilmLookupService.SHARED_FAILURE));
        String expected = "The film lookup failed. Referanse: " + log.matched.get(0).reference();
        assertThat(errorEntry(result, "a")).containsEntry("message", expected);
        assertThat(errorEntry(result, "b")).containsEntry("message", expected);
    }

    @Test
    void lambdaLog_implementingOnlyUnmatched_seesNoCaughtException_andThePayloadIsUnchanged() {
        var unmatchedReferences = new CopyOnWriteArrayList<String>();
        var result = run(
            runtime().withFetcherErrorLog((thrown, reference, env) -> unmatchedReferences.add(reference)),
            "{ filmLookup(id: 0) { " + LOOKUP_ERRORS + " } }", null);

        assertThat(errorEntry(result, "filmLookup")).containsEntry("message", "film 0 not found");
        assertThat(unmatchedReferences).isEmpty();
    }

    // ===== FetcherErrorResponse =====

    @Test
    void matchedMessage_quotesTheRequestsTraceId_whichMatchedReceivedToo_andLeavesAnotherMemberAlone() {
        var log = new RecordingLog();
        var result = run(runtime().withFetcherErrorLog(log).withFetcherErrorResponse(new TracingResponse()),
            "{ a: filmLookup(id: 779) { " + LOOKUP_ERRORS + " } b: filmLookup(id: -7) { " + LOOKUP_ERRORS + " } }",
            "trace-4711");

        assertThat(result.getErrors()).isEmpty();
        assertThat(errorEntry(result, "a"))
            .containsEntry("__typename", "FilmLookupCatchAll")
            .containsEntry("message", "The film lookup failed. Referanse: trace-4711");
        // FilmLookupInvalid's description: is left alone: the response scopes itself by parent type.
        assertThat(errorEntry(result, "b"))
            .containsEntry("__typename", "FilmLookupInvalid")
            .containsEntry("message", "The film id could not be used for a lookup.");
        assertThat(log.matched).extracting(Matched::reference).containsOnly("trace-4711").hasSize(2);
    }

    @Test
    void matchedMessage_reachesASourceThatIsAlsoAGraphQLError_onATypeWithoutDescription() {
        // No handler on FilmLookupGraphQLErrorSource carries a description:, the one layout in which
        // the message fetcher used to run its GraphQLError arm first and bypass the Throwable block.
        var result = run(runtime().withFetcherErrorResponse(new TracingResponse()),
            "{ filmLookup(id: 778) { " + LOOKUP_ERRORS + " } }", "trace-778");

        assertThat(result.getErrors()).isEmpty();
        assertThat(errorEntry(result, "filmLookup"))
            .containsEntry("__typename", "FilmLookupGraphQLErrorSource")
            .containsEntry("message", "raw getMessage from a GraphQLError source Referanse: trace-778");
    }

    @Test
    void unmatchedMessage_replacesTheTopLevelText_withTheReferenceTheLogReceived() {
        var log = new RecordingLog();
        var result = run(runtime().withFetcherErrorLog(log).withFetcherErrorResponse(new TracingResponse()),
            durabilityQuery(), null);

        assertThat(log.unmatched).singleElement().satisfies(u -> assertThat(u.reference()).matches(DEFAULT_REFERENCE));
        assertThat(result.getErrors()).extracting(GraphQLError::getMessage).containsExactly(
            "An exception occurred. The error has been logged with id " + log.unmatched.get(0).reference() + ".");
    }

    @Test
    void aResponseThatThrowsOrReturnsNull_leavesTheClientTheDefaultText() {
        var response = new FetcherErrorResponse() {
            @Override
            public String matchedMessage(Throwable source, String defaultMessage, String reference,
                                         DataFetchingEnvironment env) {
                throw new AssertionError("the consumer's response is broken");
            }

            @Override
            public String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env) {
                return null;
            }
        };
        var runtime = runtime().withFetcherErrorResponse(response);

        var matched = run(runtime, "{ filmLookup(id: 779) { " + LOOKUP_ERRORS + " } }", null);
        assertThat(matched.getErrors()).isEmpty();
        assertThat(errorEntry(matched, "filmLookup")).containsEntry("message", "The film lookup failed.");

        var unmatched = run(runtime, durabilityQuery(), null);
        assertThat(unmatched.getErrors()).extracting(GraphQLError::getMessage)
            .singleElement().asString()
            .matches("An error occurred\\. Reference: " + DEFAULT_REFERENCE + "\\.");
    }

    @Test
    void escapeHatch_readsTheResponseFromTheRequestsContext() {
        var engine = Graphitron.newGraphQL().build();
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query(durabilityQuery())
            .graphQLContext(b -> b.put(FetcherErrorResponse.class, new TracingResponse()).put(TRACE_ID, "trace-hatch"))
            .build();

        var result = engine.execute(input);

        assertThat(result.getErrors()).extracting(GraphQLError::getMessage)
            .containsExactly("An exception occurred. The error has been logged with id trace-hatch.");
    }

    // ===== helpers =====

    private static GraphitronRuntime runtime() {
        return Graphitron.runtime(dataSource, SQLDialect.POSTGRES);
    }

    private static ExecutionResult run(GraphitronRuntime runtime, String query, String traceId) {
        var engine = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
        var input = Graphitron.newOwnedExecutionInput("{\"sub\":\"test-user\"}", "test-user").query(query);
        if (traceId != null) {
            input.graphQLContext(b -> b.put(TRACE_ID, traceId));
        }
        return engine.execute(input.build());
    }

    /** One film whose {@code durabilityError} throws, unmatched: a single top-level error. */
    private static String durabilityQuery() {
        return "{ filmById(film_id: [\"1\"]) { durabilityError } }";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorEntry(ExecutionResult result, String field) {
        Map<String, Object> data = result.getData();
        var payload = (Map<String, Object>) data.get(field);
        var errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }
}
