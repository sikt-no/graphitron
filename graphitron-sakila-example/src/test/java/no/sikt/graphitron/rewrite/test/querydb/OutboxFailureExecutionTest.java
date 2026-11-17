package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronConnectionInstrumentation;
import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider;
import no.sikt.graphitron.generated.schema.OwnedConnections;
import no.sikt.graphitron.generated.schema.AcquisitionFailedException;
import no.sikt.graphitron.generated.schema.ConnectionSources;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import no.sikt.graphitron.sakila.example.app.SakilaSessions;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier coverage for {@code @outbox(on: ERROR)}, through GraphQL against PostgreSQL, on
 * {@code Mutation.insertFilmReportingFailure}: a {@code FilmReported} message when the insert commits,
 * and when it rolls back the failure message {@code Mutation.insertFilmReportingFailure.failed},
 * carrying the error the field's {@code @error} channel claimed it as ({@code kind}, {@code detail}),
 * the title asked for and the mounted principal ({@code by}).
 *
 * <p>Each case finds its rows by a title only it wrote, and its log lines by an alias only it used,
 * which the field's path in every line the carrier logs names. The connection cases are built on what
 * {@code ServerEndedSessionPinTest} shows pgjdbc raising: the statement after the server ended a
 * session over its transaction raises {@code 25P03}, after a terminated backend {@code 57P01}.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class OutboxFailureExecutionTest {

    private static final String FAILED = "Mutation.insertFilmReportingFailure.failed";
    private static final String CLAIMS = "{\"sub\":\"alice\"}";

    static DSLContext dsl;

    @BeforeAll
    static void start() {
        dsl = SakilaDatabase.dsl();
    }

    @AfterAll
    static void removeWhatWasWritten() {
        dsl.execute("delete from film where title like 'outbox-failure-%'");
        dsl.execute("delete from outbox where (type = ? and payload ->> 'title' like 'outbox-failure-%')"
            + " or (type = 'FilmReported' and payload ->> 'reported' like 'outbox-failure-%')"
            + " or (type = 'MarkerFailed' and payload ->> 'marker' like 'outbox-failure-%')", FAILED);
    }

    private static String marker() {
        return "outbox-failure-" + UUID.randomUUID();
    }

    private static String alias() {
        return "f" + UUID.randomUUID().toString().replace("-", "");
    }

    // --- engines -----------------------------------------------------------------------------------

    private static PGSimpleDataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(SakilaDatabase.url());
        dataSource.setUser(SakilaDatabase.user());
        dataSource.setPassword(SakilaDatabase.password());
        return dataSource;
    }

    /** An engine and the pool its requests lease from. */
    private record Engine(GraphQL graphql, DataSource pool) {}

    private static Engine engine(GraphitronRuntime runtime, DataSource pool,
                                 graphql.execution.instrumentation.Instrumentation... more) {
        return new Engine(runtime.newGraphQL(Graphitron.buildSchema(b -> {}), more).build(), pool);
    }

    private static Engine engine(DataSource pool, graphql.execution.instrumentation.Instrumentation... more) {
        return engine(Graphitron.runtime(SQLDialect.POSTGRES), pool, more);
    }

    private static Engine engine() {
        return engine(dataSource());
    }

    private static ExecutionResult execute(Engine engine, String query) {
        return engine.graphql().execute(Graphitron.newOwnedExecutionInput(SakilaSessions.of(CLAIMS, engine.pool()), CLAIMS, "alice")
            .query(query).build());
    }

    /**
     * {@link #execute}, for an operation whose field's connection the server ended: releasing that
     * dead connection at the end of the operation evicts it without failing the operation, so the
     * response the operation computed reaches the caller, the field failed at its path.
     */
    private static ExecutionResult executeOnADyingSession(Engine engine, String query, String alias) {
        var result = execute(engine, query);
        assertThat(result.getErrors()).as("the field whose connection died fails at its path, and only it")
            .isNotEmpty()
            .allSatisfy(e -> assertThat(e.getPath()).first().isEqualTo(alias));
        assertThat((Map<String, Object>) result.getData()).as("the response is computed and returned")
            .containsKey(alias);
        return result;
    }

    private static String insert(String alias, String in) {
        return alias + ": insertFilmReportingFailure(in: " + in + ") { film { id } errors { __typename"
            + " ... on FilmInsertLanguageMissing { message } ... on FilmInsertValueMissing { message } } }";
    }

    // --- reading back ------------------------------------------------------------------------------

    private static List<Map<String, Object>> failures(String title) {
        return dsl.fetch("select payload -> 'kind' is not null as has_kind, payload -> 'detail' is not null"
                + " as has_detail, payload ->> 'kind' as kind, payload ->> 'detail' as detail,"
                + " payload ->> 'by' as by from outbox where type = ? and payload ->> 'title' = ?", FAILED, title)
            .intoMaps();
    }

    private static int failureCount(String title) {
        return failures(title).size();
    }

    private static int reported(String title) {
        return dsl.fetchCount(dsl.selectFrom("outbox").where("type = 'FilmReported' and payload ->> 'reported' = ?",
            title));
    }

    private static int films(String title) {
        return dsl.fetchCount(dsl.selectFrom("film").where("title = ?", title));
    }

    // --- the outcomes ------------------------------------------------------------------------------

    @Test
    void aClaimedForeignKeyViolation_writesTheFailureMessage_andNothingTheFieldWrote() {
        var title = marker();
        var a = alias();
        var result = execute(engine(), "mutation { " + insert(a, "{title: \"" + title + "\", languageId: 999999}") + " }");

        assertThat(result.getErrors()).as("claimed into the payload").isEmpty();
        var error = ((List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) result.getData()).get(a))
            .get("errors")).getFirst();
        assertThat(error).containsEntry("__typename", "FilmInsertLanguageMissing");
        assertThat(films(title)).as("the write rolled back").isZero();
        assertThat(reported(title)).as("and the success message with it").isZero();
        assertThat(failures(title)).singleElement().satisfies(row -> assertThat(row)
            .as("the type and message the client was shown")
            .containsEntry("kind", "FilmInsertLanguageMissing")
            .containsEntry("detail", error.get("message"))
            .as("read on the field's connection, its session still mounted")
            .containsEntry("by", "alice"));
    }

    @Test
    void anUnclaimedFailure_writesTheFailureMessage_withoutItsErrorKeys() {
        // The outbox table refuses the field's own success message for this title, so the field fails
        // with the outbox's exception, which no @error channel may claim: the client sees it redacted.
        var title = "outbox-failure-report-refused-on-write";
        int before = failureCount(title);
        var result = execute(engine(), "mutation { " + insert(alias(), "{title: \"" + title + "\", languageId: 1}") + " }");

        assertThat(result.getErrors()).as("redacted").isNotEmpty();
        assertThat(films(title)).isZero();
        assertThat(failureCount(title)).isEqualTo(before + 1);
        assertThat(failures(title).getLast())
            .containsEntry("has_kind", false)
            .containsEntry("has_detail", false)
            .containsEntry("by", "alice");
    }

    @Test
    void aValidationRefusal_writesTheFailureMessage_andTheResponseStillCarriesTheTypedError() {
        // Longer than film.title's 255 characters: the VALIDATION handler refuses it before any statement.
        var title = marker() + "-" + "x".repeat(255);
        var a = alias();
        var result = execute(engine(), "mutation { " + a + ": insertFilmReportingFailure(in: {title: \"" + title
            + "\", languageId: 1}) { film { id } errors { __typename ... on FilmWriteInvalid { path message } } } }");

        assertThat(result.getErrors()).as("the refusal is payload data, not an error").isEmpty();
        var payload = (Map<String, Object>) ((Map<String, Object>) result.getData()).get(a);
        assertThat(payload).as("the typed payload is returned, not nulled by the rollback").isNotNull()
            .containsEntry("film", null);
        var error = ((List<Map<String, Object>>) payload.get("errors")).getFirst();
        assertThat(error).containsEntry("__typename", "FilmWriteInvalid")
            .containsEntry("path", List.of(a, "in", "title"))
            .containsEntry("message", "size must be between 0 and 255");
        assertThat(films(title)).isZero();
        assertThat(reported(title)).isZero();
        assertThat(failures(title)).singleElement().satisfies(row -> assertThat(row)
            .as("the refusal, as the client was shown it")
            .containsEntry("kind", "FilmWriteInvalid")
            .containsEntry("detail", error.get("message"))
            .containsEntry("by", "alice"));
    }

    @Test
    void aSuccess_writesNoFailureMessage() {
        var title = marker();
        var result = execute(engine(), "mutation { " + insert(alias(), "{title: \"" + title + "\", languageId: 1}") + " }");

        assertThat(result.getErrors()).isEmpty();
        assertThat(films(title)).isEqualTo(1);
        assertThat(reported(title)).isEqualTo(1);
        assertThat(failures(title)).isEmpty();
    }

    @Test
    void inTwoFields_theFirstsFailureAndTheSecondsSuccess_bothCommit() {
        var failing = marker();
        var succeeding = marker();
        var result = execute(engine(), "mutation { "
            + insert(alias(), "{title: \"" + failing + "\", languageId: 999999}") + " "
            + insert(alias(), "{title: \"" + succeeding + "\", languageId: 1}") + " }");

        assertThat(result.getErrors()).isEmpty();
        assertThat(failures(failing)).hasSize(1);
        assertThat(reported(failing)).isZero();
        assertThat(reported(succeeding)).isEqualTo(1);
        assertThat(failures(succeeding)).isEmpty();
        assertThat(films(succeeding)).isEqualTo(1);
    }

    @Test
    void aFailureMessageThatCannotBeWritten_isLogged_andTheResponseIsUnchanged() {
        // The outbox table refuses a message whose title is this marker, as it is written.
        var title = "outbox-failure-refused-on-write";
        var a = alias();
        try (var log = OwnedConnectionsLog.capture()) {
            var result = execute(engine(), "mutation { " + insert(a, "{title: \"" + title + "\", languageId: 999999}") + " }");

            assertThat(result.getErrors()).as("the field failed as it would have without the message").isEmpty();
            var data = (Map<String, Object>) ((Map<String, Object>) result.getData()).get(a);
            assertThat(data.get("film")).isNull();
            assertThat((List<Map<String, Object>>) data.get("errors")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("__typename", "FilmInsertLanguageMissing"));
            assertThat(log.mentioning(a)).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("failed after its transaction rolled back"));
        }
    }

    @Test
    void aCommitThatFailsOnADeferredConstraint_rollsTheWriteBack_andWritesTheFailureMessage() {
        // The outbox table refuses a FilmReported message for this title at COMMIT, a deferred trigger.
        var title = "outbox-failure-refused-at-commit";
        int before = failureCount(title);
        var result = execute(engine(), "mutation { " + insert(alias(), "{title: \"" + title + "\", languageId: 1}") + " }");

        assertThat(result.getErrors()).as("the failed commit fails the field").isNotEmpty();
        assertThat(films(title)).isZero();
        assertThat(reported(title)).isZero();
        assertThat(failureCount(title)).as("written once the rollback was done").isEqualTo(before + 1);
        assertThat(failures(title).getLast())
            .as("nothing claimed a failure found only at COMMIT")
            .containsEntry("has_kind", false);
    }

    @Test
    void underRollbackOnly_theFailureMessageIsWrittenInsideTheOperation_andDiscardedWithIt() {
        var runtime = Graphitron.runtime(SQLDialect.POSTGRES);
        var devEngine = new Engine(GraphQL.newGraphQL(Graphitron.buildSchema(b -> {}))
            .instrumentation(new GraphitronConnectionInstrumentation(runtime,
                GraphitronTransactionProvider.CommitPolicy.ROLLBACK_ONLY))
            .build(), dataSource());
        var title = marker();
        var refused = alias();
        try (var log = OwnedConnectionsLog.capture()) {
            execute(devEngine, "mutation { "
                + insert(refused, "{title: \"outbox-failure-refused-on-write\", languageId: 999999}") + " "
                + insert(alias(), "{title: \"" + title + "\", languageId: 999999}") + " }");

            assertThat(log.mentioning(refused))
                .as("the message was written, inside the operation's transaction: the outbox refused it")
                .hasSize(1);
        }
        assertThat(failures(title)).as("and the one it took is gone with the operation").isEmpty();
    }

    @Test
    void onTheEscapeHatch_nothingIsWritten_andAWarningNamesTheField() {
        var title = marker();
        var a = alias();
        try (var log = OwnedConnectionsLog.capture()) {
            var result = Graphitron.newGraphQL().build().execute(Graphitron.newExecutionInput(dsl, CLAIMS, "alice")
                .query("mutation { " + insert(a, "{title: \"" + title + "\", languageId: 999999}") + " }").build());

            assertThat(result.getErrors()).isEmpty();
            assertThat(failures(title)).isEmpty();
            assertThat(log.mentioning(a)).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("caller-supplied DSLContext").contains(FAILED));
        }
    }

    @Test
    void aSessionMountThatFails_writesNothing_andTheLossIsLogged() {
        var title = marker();
        var a = alias();
        var pool = dataSource();
        var rejecting = ConnectionSources.mounting(key -> pool, (connection, key) -> {
            throw new IllegalStateException("identity rejected");
        }, (connection, key, handle) -> {});
        try (var log = OwnedConnectionsLog.capture()) {
            assertThatThrownBy(() -> engine(pool).graphql().execute(Graphitron.newOwnedExecutionInput(rejecting, CLAIMS, "alice")
                    .query("mutation { " + insert(a, "{title: \"" + title + "\", languageId: 999999}") + " }").build()))
                .isInstanceOf(AcquisitionFailedException.class);

            assertThat(failures(title)).isEmpty();
            assertThat(log.mentioning(a)).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("the connection source failed to lease a connection"));
        }
    }

    @Test
    void aServiceThatThrows_writesItsFailureMessage_registeredWhereItsFrameStarts() {
        var marker = "outbox-failure-" + UUID.randomUUID();
        var result = execute(engine(), "mutation { outboxScopeWriteTwiceThenThrow(marker: \"" + marker + "\") }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(dsl.fetchCount(dsl.selectFrom("mutation_scope_marker").where("marker = ?", marker)))
            .as("the service's writes rolled back").isZero();
        assertThat(dsl.fetchCount(dsl.selectFrom("outbox").where("type = 'MarkerThrown' and payload ->> 'marker' = ?",
            marker))).isZero();
        assertThat(dsl.fetch("select payload ->> 'at' as at from outbox where type = 'MarkerFailed'"
                + " and payload ->> 'marker' = ?", marker).intoMaps())
            .singleElement()
            .satisfies(row -> assertThat(row).containsEntry("at", "Mutation.outboxScopeWriteTwiceThenThrow"));
    }

    // --- the connection the transaction cost ---------------------------------------------------------

    /** Runs {@code action} when the field's {@code errors} are fetched: after its write, before it settles. */
    private static SimplePerformantInstrumentation whilePayloadResolves(String alias, Runnable action) {
        return new SimplePerformantInstrumentation() {
            @Override
            public FieldFetchingInstrumentationContext beginFieldFetching(InstrumentationFieldFetchParameters parameters,
                                                                         InstrumentationState state) {
                if (parameters.getExecutionStepInfo().getPath().toString().equals("/" + alias + "/errors")) {
                    action.run();
                }
                return super.beginFieldFetching(parameters, state);
            }
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void aSessionTheServerEndedOverTheTransaction_writesTheFailureMessageOnAFreshConnection() {
        var title = marker();
        var a = alias();
        var dataSource = dataSource();
        dataSource.setOptions("-c idle_in_transaction_session_timeout=300");
        var engine = engine(dataSource, whilePayloadResolves(a, () -> sleep(1_000)));

        executeOnADyingSession(engine, "mutation { " + insert(a, "{title: \"" + title + "\", languageId: 1}") + " }", a);

        assertThat(films(title)).isZero();
        assertThat(reported(title)).isZero();
        assertThat(failures(title)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("has_kind", false)
            .as("on a fresh connection the session was mounted on again")
            .containsEntry("by", "alice"));
    }

    @Test
    void aSessionTheServerEndedOverTheTransaction_isReadFromSettlesProbe_whenNothingElseRanAfter() {
        // No payload field reads the database after the pause, so the first statement on the dead
        // session is settle's own probe, and that is what names the transaction.
        var title = marker();
        var a = alias();
        var dataSource = dataSource();
        dataSource.setOptions("-c idle_in_transaction_session_timeout=300");
        var engine = engine(dataSource, whilePayloadResolves(a, () -> sleep(1_000)));

        executeOnADyingSession(engine, "mutation { " + a + ": insertFilmReportingFailure(in: {title: \"" + title
            + "\", languageId: 1}) { errors { __typename } } }", a);

        assertThat(films(title)).isZero();
        assertThat(failures(title)).singleElement().satisfies(row -> assertThat(row).containsEntry("by", "alice"));
    }

    @Test
    void aFreshConnectionThatIsNotThereWithinTheBound_isGivenUp_andTheOperationIsNotHeldLonger() {
        var title = marker();
        var a = alias();
        var plain = dataSource();
        plain.setOptions("-c idle_in_transaction_session_timeout=300");
        var exhausted = new CountDownLatch(1);
        var runtime = Graphitron.runtime(SQLDialect.POSTGRES)
            .withOutboxAcquisitionTimeout(Duration.ofMillis(500));
        var engine = engine(runtime, exhaustedAfterFirst(plain, exhausted), whilePayloadResolves(a, () -> sleep(1_000)));
        try (var log = OwnedConnectionsLog.capture()) {
            long started = System.nanoTime();
            executeOnADyingSession(engine, "mutation { " + insert(a, "{title: \"" + title + "\", languageId: 1}") + " }", a);
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(took).as("the payload's second and the bound's half, and some slack, but no more")
                .isLessThan(1_000 + 500 + 3_000);
            assertThat(failures(title)).isEmpty();
            assertThat(log.mentioning(a)).singleElement().satisfies(r -> assertThat(r.getMessage())
                .contains("outboxAcquisitionTimeout").contains("queued for the executor").contains("acquiring"));
        } finally {
            exhausted.countDown();
        }
        assertThat(failures(title)).as("a connection that arrives late is never written on").isEmpty();
    }

    @Test
    void aBackendTerminatedFromOutside_dropsTheFailureMessage_andTheLossIsLogged() {
        var title = marker();
        var a = alias();
        var dataSource = dataSource();
        dataSource.setApplicationName(a);
        var engine = engine(dataSource, whilePayloadResolves(a, () -> {
            dsl.execute("select pg_terminate_backend(pid) from pg_stat_activity where application_name = ?", a);
            sleep(200);
        }));
        try (var log = OwnedConnectionsLog.capture()) {
            executeOnADyingSession(engine, "mutation { " + insert(a, "{title: \"" + title + "\", languageId: 1}") + " }", a);

            assertThat(films(title)).isZero();
            assertThat(failures(title)).isEmpty();
            assertThat(log.mentioning(a)).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("outside the transaction"));
        }
    }

    /** {@code inner}, whose every {@code getConnection} after the first parks until {@code released}: a pool run dry. */
    private static DataSource exhaustedAfterFirst(DataSource inner, CountDownLatch released) {
        var taken = new AtomicInteger();
        return (DataSource) Proxy.newProxyInstance(OutboxFailureExecutionTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection") && taken.incrementAndGet() > 1) {
                    released.await(30, TimeUnit.SECONDS);
                }
                try {
                    return method.invoke(inner, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    /** The records the generated connection carrier logs while a block runs. */
    static final class OwnedConnectionsLog implements AutoCloseable {
        private final java.util.logging.Logger logger =
            org.jboss.logmanager.Logger.getLogger(OwnedConnections.class.getName());
        private final List<LogRecord> recorded = Collections.synchronizedList(new ArrayList<>());
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { recorded.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };

        private OwnedConnectionsLog() {
            logger.addHandler(handler);
        }

        static OwnedConnectionsLog capture() {
            return new OwnedConnectionsLog();
        }

        /** The records whose message names {@code text}, which a case makes one only it uses. */
        List<LogRecord> mentioning(String text) {
            synchronized (recorded) {
                return recorded.stream()
                    .filter(r -> r.getMessage() != null && r.getMessage().contains(text))
                    .toList();
            }
        }

        @Override
        public void close() {
            logger.removeHandler(handler);
        }
    }
}
