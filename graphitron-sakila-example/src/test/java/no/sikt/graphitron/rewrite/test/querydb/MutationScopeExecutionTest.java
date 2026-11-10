package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.AsyncExecutionStrategy;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronConnectionInstrumentation;
import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier proof that every top-level mutation field on the owned-connection path runs as
 * one transaction, payload included, settled when the next field starts or the operation
 * completes. The fixtures are {@code MutationScopeService}'s {@code @service} mutations, which
 * write marker rows into {@code mutation_scope_marker} on the {@code DSLContext} graphitron hands
 * them and then end the ways a field can end; each test reads back, out of band, which markers
 * survived.
 *
 * <p>The contract pinned here: a field the client is told succeeded has committed, and a field
 * that failed has written nothing, whether it threw, had its exception claimed into an
 * {@code @error} payload, left its transaction aborted by a statement it caught, or failed at
 * commit. Fields commit independently, in document order.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class MutationScopeExecutionTest {

    static DSLContext dsl;               // out-of-band, for assertions and cleanup
    static String jdbcUrl, jdbcUser, jdbcPassword;
    static final AtomicInteger CONNECTIONS_OPENED = new AtomicInteger();
    /** What every connection the runtime pinned was asked to do about transactions, in order. */
    static final List<String> CONNECTION_EVENTS = java.util.Collections.synchronizedList(new ArrayList<>());
    static GraphitronRuntime runtime;
    static GraphQL graphql;

    private final List<String> markers = new ArrayList<>();
    private final List<String> filmTitles = new ArrayList<>();

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = SakilaDatabase.url();
        jdbcUser = SakilaDatabase.user();
        jdbcPassword = SakilaDatabase.password();

        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);
        runtime = Graphitron.runtime(countingDataSource(), SQLDialect.POSTGRES);
        graphql = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }


    @AfterEach
    void cleanUp() {
        dsl.deleteFrom(DSL.table("mutation_scope_deferred_child"))
            .where(DSL.field("marker", String.class).in(markers)).execute();
        dsl.deleteFrom(DSL.table("mutation_scope_marker"))
            .where(DSL.field("marker", String.class).in(markers)).execute();
        dsl.deleteFrom(DSL.table("film")).where(DSL.field("title", String.class).in(filmTitles)).execute();
    }

    // ===== a field's writes stand or fall together =====

    @Test
    void aThrowingServiceField_rollsBackBothItsWrites_whileTheFieldBeforeItCommits() {
        String good = marker(), bad = marker();

        ExecutionResult result = execute("""
            mutation {
                good: scopeWriteOne(marker: "%s")
                bad: scopeWriteTwiceThenThrow(marker: "%s")
            }
            """.formatted(good, bad));

        assertThat(errorPaths(result)).containsExactly(List.of("bad"));
        assertThat(rows(good)).as("the successful field committed").isEqualTo(1);
        assertThat(rows(bad))
            .as("both writes, made in what the service saw as autocommit, rolled back with the field")
            .isZero();
    }

    @Test
    void aClaimedException_answersItsPayload_andStillRollsBackTheFieldsWrites() {
        String refused = marker();

        ExecutionResult result = execute("""
            mutation {
                refused: scopeWriteTwiceThenRefuse(marker: "%s") { marker errors { __typename } }
            }
            """.formatted(refused));

        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> payload = (Map<String, Object>) data(result).get("refused");
        assertThat((List<Map<String, Object>>) payload.get("errors"))
            .extracting(e -> e.get("__typename"))
            .as("the @error channel turned the exception into business data")
            .containsExactly("MutationScopeRefused");
        assertThat(rows(refused)).as("a claimed exception still fails the field").isZero();
    }

    // ===== a transaction opened inside the field joins it =====

    private static final String NESTED_FAILED = "A nested transaction in this field failed; the field's writes were rolled back; reference = ";

    @Test
    void aNestedScopeThatSucceeds_joinsTheFieldsTransaction_takingNoSavepoint() {
        String marker = marker();
        CONNECTION_EVENTS.clear();

        ExecutionResult result = execute("mutation { n: scopeNestedSucceeds(marker: \"%s\") }".formatted(marker));

        assertThat(result.getErrors()).isEmpty();
        assertThat(data(result).get("n")).isEqualTo(marker);
        assertThat(rows(marker)).as("both writes committed with the field").isEqualTo(2);
        assertThat(CONNECTION_EVENTS)
            .as("the nested scope took no savepoint, and the field committed once")
            .doesNotContain("setSavepoint")
            .filteredOn("commit"::equals).hasSize(1);
    }

    @Test
    void aNestedFailure_caughtByTheService_stillFailsTheField_withTheNamedError() {
        String caught = marker(), next = marker();

        ExecutionResult result = execute("""
            mutation {
                n: scopeNestedFailsCaught(marker: "%s")
                next: scopeWriteOne(marker: "%s")
            }
            """.formatted(caught, next));

        assertThat(data(result).get("n")).as("the field returned normally, but it rolled back").isNull();
        assertThat(errorPaths(result)).containsExactly(List.of("n"));
        assertThat(result.getErrors().get(0).getMessage()).startsWith(NESTED_FAILED);
        assertThat(rows(caught)).as("nothing it wrote, before or after the nested scope, stayed").isZero();
        assertThat(rows(next)).as("the next field ran and committed").isEqualTo(1);
    }

    @Test
    void aNestedSqlFailure_caughtByTheService_failsTheNextStatement_andTheFieldCarriesBothErrors() {
        String marker = marker();

        ExecutionResult result = execute(
            "mutation { n: scopeNestedSqlFailCaught(marker: \"%s\") }".formatted(marker));

        assertThat(data(result).get("n")).isNull();
        assertThat(errorPaths(result)).as("the router's redacted error and the named one, beside each other")
            .containsExactly(List.of("n"), List.of("n"));
        assertThat(result.getErrors()).extracting(GraphQLError::getMessage)
            .anySatisfy(m -> assertThat(m).startsWith("An error occurred. Reference: "))
            .anySatisfy(m -> assertThat(m).startsWith(NESTED_FAILED));
        assertThat(rows(marker)).isZero();
    }

    @Test
    void aNestedFailure_notCaught_failsTheFieldWithTheRoutersErrorAndTheNamedOne() {
        String marker = marker();

        ExecutionResult result = execute(
            "mutation { n: scopeNestedFailsUncaught(marker: \"%s\") }".formatted(marker));

        assertThat(data(result).get("n")).isNull();
        assertThat(result.getErrors()).extracting(GraphQLError::getMessage)
            .anySatisfy(m -> assertThat(m).startsWith("An error occurred. Reference: "))
            .anySatisfy(m -> assertThat(m).startsWith(NESTED_FAILED));
        assertThat(rows(marker)).isZero();
    }

    @Test
    void aGeneratedMutationThatFails_answersWithTheRoutersErrorOnly_noNamedNestedFailure() {
        String title = "MUTATION-SCOPE-" + UUID.randomUUID();
        filmTitles.add(title);
        CONNECTION_EVENTS.clear();

        ExecutionResult result = execute(
            "mutation { c: createFilm(in: { title: \"%s\", languageId: 999 }) { filmId } }".formatted(title));

        assertThat(data(result).get("c")).isNull();
        assertThat(result.getErrors()).as("graphitron's own nested scope failing adds no second error").hasSize(1);
        assertThat(result.getErrors().get(0).getMessage()).doesNotContain("nested transaction");
        assertThat(CONNECTION_EVENTS).doesNotContain("setSavepoint");
    }

    @Test
    void aQueryServiceThatOpensATransaction_nestsWithoutASavepoint_andACaughtNestedFailureFailsItsCommit() {
        CONNECTION_EVENTS.clear();

        ExecutionResult result = graphql.execute(ownedInput(
            "{ scopeQueryNestedCaught(marker: \"q\") }").build());

        assertThat(errorPaths(result)).containsExactly(List.of("scopeQueryNestedCaught"));
        assertThat(CONNECTION_EVENTS).doesNotContain("setSavepoint");
    }

    // ===== the value a rolled-back field shows =====

    @Test
    void aFieldThatRolledBackForAnErrorUnderItsPath_isNull_andItsWriteIsGone() {
        String marker = marker();

        ExecutionResult result = execute(
            "mutation { w: scopeWriteThenFailingPayload(marker: \"%s\") { marker failing } }".formatted(marker));

        assertThat(errorPaths(result)).containsExactly(List.of("w", "failing"));
        assertThat(data(result).get("w"))
            .as("no value describes writes that are gone, not even the part that read fine")
            .isNull();
        assertThat(rows(marker)).isZero();
    }

    @Test
    void theSamePayload_withoutTheFailingSelection_commitsAndKeepsItsValue() {
        String marker = marker();

        ExecutionResult result = execute(
            "mutation { w: scopeWriteThenFailingPayload(marker: \"%s\") { marker } }".formatted(marker));

        assertThat(result.getErrors()).isEmpty();
        assertThat((Map<String, Object>) data(result).get("w")).containsEntry("marker", marker);
        assertThat(rows(marker)).isEqualTo(1);
    }

    // ===== a service's nested scope is warned about, once per method =====

    private static final org.jboss.logmanager.formatters.PatternFormatter MESSAGE_ONLY =
        new org.jboss.logmanager.formatters.PatternFormatter("%s");

    @Test
    void aServiceThatNestsATransaction_isWarnedOncePerMethod_namingTheFieldAndTheMethod() {
        // The test classpath logs through slf4j-jboss-logmanager, so the carrier's logger is the
        // JBoss LogManager's, and the line is read off a handler attached to it.
        var logger = org.jboss.logmanager.Logger.getLogger("no.sikt.graphitron.generated.schema.TenantConnections");
        var warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
        // The logger is the JVM's, and other classes run beside this one: their requests warn on
        // their own threads, while this one's runs to completion on the test's thread.
        long self = Thread.currentThread().threadId();
        var handler = new org.jboss.logmanager.ExtHandler() {
            @Override
            protected void doPublish(org.jboss.logmanager.ExtLogRecord record) {
                if (record.getLongThreadID() == self
                        && record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(MESSAGE_ONLY.format(record));
                }
            }
        };
        logger.addHandler(handler);
        try {
            execute("mutation { w: scopeNestedForWarning(marker: \"%s\") }".formatted(marker()));
            execute("mutation { w: scopeNestedForWarning(marker: \"%s\") }".formatted(marker()));
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(warnings)
            .as("one line for the method, not one per request")
            .filteredOn(m -> m.contains("joined the enclosing transaction"))
            .filteredOn(m -> m.contains("MutationScopeService#nestedScopeForWarning"))
            .singleElement()
            .satisfies(m -> assertThat(m)
                .contains("MutationScopeService#nestedScopeForWarning")
                .contains("mutation field: [w]"));
    }

    // ===== every place graphitron opens its own scope, inside a field =====

    /**
     * One mutation per site that renders a generated transaction: the re-select arm, the bulk loop,
     * the ID-returning arm, the {@code @outbox} write, and the two routine writes. Each opens its
     * own scope inside the field's, which must join without a savepoint and without the warning a
     * consumer's nested scope draws, and the field commits once.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"reSelect", "bulkLoop", "idReturning", "outbox", "routineChain", "routineCarrier"})
    void aGeneratedWrite_opensAnOwnScopeThatJoinsSilently_andTheFieldCommitsOnce(String site) {
        int rentalsBefore = maxRentalId();
        String title = "MUTATION-SCOPE-" + UUID.randomUUID();
        filmTitles.add(title);
        String document = switch (site) {
            case "reSelect" -> "mutation { w: createFilm(in: { title: \"%s\", languageId: 1 }) { filmId } }"
                .formatted(title);
            case "bulkLoop" -> "mutation { w: createFilms(in: [{ title: \"%s\", languageId: 1 }]) { filmId } }"
                .formatted(title);
            case "idReturning" -> {
                int filmId = dsl.insertInto(DSL.table("film"), DSL.field("title"), DSL.field("language_id"))
                    .values(title, 1).returningResult(DSL.field("film_id", Integer.class))
                    .fetchOne(0, Integer.class);
                yield "mutation { w: deleteFilms(in: [{ filmId: %d }]) }".formatted(filmId);
            }
            case "outbox" -> "mutation { w: createFilmOutbox(in: { title: \"%s\", languageId: 1 }) { filmId } }"
                .formatted(title);
            case "routineChain" -> "mutation { w: rentFilm(inventoryId: 2, customerId: %d) { rentalId } }".formatted(RENTER);
            case "routineCarrier" -> "mutation { w: rentFilmPayload(inventoryId: 2, customerId: %d) {".formatted(RENTER)
                + " rental { rentalId } errors { __typename } } }";
            default -> throw new IllegalArgumentException(site);
        };
        CONNECTION_EVENTS.clear();
        ExecutionResult result;
        List<String> warnings;
        try {
            var captured = warningsDuring(() -> execute(document));
            result = captured.result();
            warnings = captured.warnings();

            assertThat(result.getErrors()).as(site).isEmpty();
            assertThat(CONNECTION_EVENTS).as(site + ": a joined own scope takes no savepoint")
                .doesNotContain("setSavepoint")
                .filteredOn("commit"::equals).as(site + ": one commit, at the field's settle").hasSize(1);
            assertThat(warnings).as(site + ": graphitron's own scope draws no consumer-scope warning")
                .noneMatch(m -> m.contains("joined the enclosing transaction"));
        } finally {
            dsl.deleteFrom(DSL.table("rental")).where(DSL.field("rental_id", Integer.class).gt(rentalsBefore))
                .and(DSL.field("customer_id", Integer.class).eq(RENTER)).execute();
            dsl.execute("delete from outbox where payload ->> 'requested' = ?", title);
        }
    }

    @Test
    void aGeneratedWriteWhoseFailureAnErrorChannelClaims_keepsItsPayload_andAddsNoNestedFailureReport() {
        String title = "MUTATION-SCOPE-" + UUID.randomUUID();
        filmTitles.add(title);
        CONNECTION_EVENTS.clear();

        ExecutionResult result = execute("""
            mutation {
                c: createFilmWithErrors(in: { title: "%s", languageId: 999 }) {
                    film { title }
                    errors { __typename }
                }
            }
            """.formatted(title));

        assertThat(result.getErrors())
            .as("the claimed failure is business data: no top-level error, and no second, misleading one"
                + " from graphitron's own scope failing underneath it")
            .isEmpty();
        Map<String, Object> payload = (Map<String, Object>) data(result).get("c");
        assertThat(payload).containsEntry("film", null);
        assertThat((List<Map<String, Object>>) payload.get("errors")).hasSize(1);
        assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(title)))
            .as("the field still rolled back").isZero();
        assertThat(CONNECTION_EVENTS).doesNotContain("setSavepoint", "commit");
    }

    private record Captured(ExecutionResult result, List<String> warnings) {}

    /** Runs {@code body}, collecting what the carrier logs at WARN or above meanwhile. */
    private Captured warningsDuring(java.util.function.Supplier<ExecutionResult> body) {
        var logger = org.jboss.logmanager.Logger.getLogger("no.sikt.graphitron.generated.schema.TenantConnections");
        var warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
        // The logger is the JVM's, and other classes run beside this one: their requests warn on
        // their own threads, while this one's runs to completion on the test's thread.
        long self = Thread.currentThread().threadId();
        var handler = new org.jboss.logmanager.ExtHandler() {
            @Override
            protected void doPublish(org.jboss.logmanager.ExtLogRecord record) {
                if (record.getLongThreadID() == self
                        && record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(MESSAGE_ONLY.format(record));
                }
            }
        };
        logger.addHandler(handler);
        try {
            return new Captured(body.get(), List.copyOf(warnings));
        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * The customer this class rents to, one no other class rents to: other classes commit rentals
     * beside this one, so a test's own are the rows above the highest id it saw, to this customer.
     * Elizabeth is inactive, which the routine does not check.
     */
    private static final int RENTER = 5;

    private int maxRentalId() {
        Integer max = dsl.select(DSL.max(DSL.field("rental_id", Integer.class))).from(DSL.table("rental"))
            .fetchOne(0, Integer.class);
        return max == null ? 0 : max;
    }

    // ===== what is not a mutation field transaction =====

    @Test
    void aQueryOperation_runsInAutocommit_neverOpeningATransaction() {
        CONNECTION_EVENTS.clear();

        ExecutionResult result = graphql.execute(ownedInput("{ sessionStores { storeId } }").build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(CONNECTION_EVENTS)
            .as("no setAutoCommit(false) and no commit: reads stay in autocommit")
            .doesNotContain("setAutoCommit:false", "commit", "setSavepoint");
    }

    @Test
    void underRollbackOnly_eachFieldIsOneSavepoint_nestedScopesJoinWithNone_andNothingIsLeft() {
        GraphQL devEngine = GraphQL.newGraphQL(Graphitron.buildSchema(b -> {}))
            .instrumentation(new GraphitronConnectionInstrumentation(
                runtime, GraphitronTransactionProvider.CommitPolicy.ROLLBACK_ONLY))
            .build();
        String one = marker(), nested = marker();
        CONNECTION_EVENTS.clear();

        ExecutionResult result = devEngine.execute(ownedInput("""
            mutation {
                a: scopeWriteOne(marker: "%s")
                b: scopeNestedSucceeds(marker: "%s")
            }
            """.formatted(one, nested)).build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(CONNECTION_EVENTS.stream().filter("setSavepoint"::equals).count())
            .as("one savepoint per field, none for the nested scope")
            .isEqualTo(2);
        assertThat(CONNECTION_EVENTS).as("nothing is committed").doesNotContain("commit");
        assertThat(rows(one)).as("the operation transaction was discarded at release").isZero();
        assertThat(rows(nested)).isZero();
    }

    @Test
    void offTheOwnedPath_aFetcherThatThrows_answersAsItAlwaysDid_withAndWithoutAnErrorChannel() {
        GraphQL escapeHatch = GraphQL.newGraphQL(Graphitron.buildSchema(b -> {})).build();
        String thrown = marker(), refused = marker();

        ExecutionResult unclaimed = escapeHatch.execute(Graphitron
            .newExecutionInput(dsl, "{\"sub\":\"test-user\"}", "test-user")
            .query("mutation { t: scopeWriteTwiceThenThrow(marker: \"%s\") }".formatted(thrown)).build());
        ExecutionResult claimed = escapeHatch.execute(Graphitron
            .newExecutionInput(dsl, "{\"sub\":\"test-user\"}", "test-user")
            .query("mutation { r: scopeWriteTwiceThenRefuse(marker: \"%s\") { errors { __typename } } }"
                .formatted(refused)).build());

        assertThat(errorPaths(unclaimed)).containsExactly(List.of("t"));
        assertThat(unclaimed.getErrors().get(0).getMessage()).startsWith("An error occurred. Reference: ");
        assertThat(claimed.getErrors()).isEmpty();
        assertThat((Map<String, Object>) data(claimed).get("r")).containsKey("errors");
    }

    @Test
    void onTheOwnedPath_aQueryThatFailsBeforeItAcquiresAConnection_answersAsItAlwaysDid() {
        int before = CONNECTIONS_OPENED.get();

        ExecutionResult result = graphql.execute(ownedInput(
            "{ scopeQueryFailsWithoutDatabase(marker: \"x\") }").build());

        assertThat(errorPaths(result)).containsExactly(List.of("scopeQueryFailsWithoutDatabase"));
        assertRedacted(result.getErrors().get(0));
        assertThat(CONNECTIONS_OPENED.get()).as("it never took a connection").isEqualTo(before);
    }

    // ===== a field reported as successful has committed =====

    @Test
    void aCaughtFailedStatement_failsTheField_whosePostgresTransactionWasAborted() {
        String caught = marker(), next = marker();

        ExecutionResult result = execute("""
            mutation {
                caught: scopeWriteThenCatchAFailedStatement(marker: "%s")
                next: scopeWriteOne(marker: "%s")
            }
            """.formatted(caught, next));

        assertThat(data(result).get("caught"))
            .as("the field returned normally, but the database discarded its transaction")
            .isNull();
        assertThat(errorPaths(result)).containsExactly(List.of("caught"));
        assertRedacted(result.getErrors().get(0));
        assertThat(rows(caught)).as("its write is gone").isZero();
        assertThat(data(result).get("next")).isEqualTo(next);
        assertThat(rows(next)).as("the next field ran and committed").isEqualTo(1);
    }

    @Test
    void aFailedCommit_failsTheField_andTheNextFieldStillCommits() {
        String orphan = marker(), next = marker();

        ExecutionResult result = execute("""
            mutation {
                orphan: scopeWriteADeferredOrphan(marker: "%s")
                next: scopeWriteOne(marker: "%s")
            }
            """.formatted(orphan, next));

        assertThat(data(result).get("orphan")).isNull();
        assertThat(errorPaths(result)).containsExactly(List.of("orphan"));
        assertRedacted(result.getErrors().get(0));
        assertThat(rows(orphan)).as("the deferred constraint failed the commit; nothing stayed").isZero();
        assertThat(rows(next)).isEqualTo(1);
    }

    @Test
    void aFailedCommit_onTheLastField_isReportedFromTheOperationsOwnSettle() {
        String first = marker(), orphan = marker();

        ExecutionResult result = execute("""
            mutation {
                first: scopeWriteOne(marker: "%s")
                orphan: scopeWriteADeferredOrphan(marker: "%s")
            }
            """.formatted(first, orphan));

        assertThat(data(result).get("first")).isEqualTo(first);
        assertThat(data(result).get("orphan")).isNull();
        assertThat(errorPaths(result)).containsExactly(List.of("orphan"));
        assertThat(rows(first)).isEqualTo(1);
        assertThat(rows(orphan)).isZero();
    }

    @Test
    void aNonNullFieldThatFailsToSettle_nullsData_byNullPropagation() {
        String first = marker(), orphan = marker();

        ExecutionResult result = execute("""
            mutation {
                first: scopeWriteOne(marker: "%s")
                orphan: scopeWriteADeferredOrphanRequired(marker: "%s")
            }
            """.formatted(first, orphan));

        assertThat(data(result)).isNull();
        assertThat(errorPaths(result)).containsExactly(List.of("orphan"));
        assertThat(rows(first)).as("fields commit independently: the first one's write stands").isEqualTo(1);
        assertThat(rows(orphan)).isZero();
    }

    // ===== the field's value, payload included, is inside its transaction =====

    @Test
    void theLastFieldsCommit_comesBeforeItsConnectionIsReleased_whateverThreadCompletesTheOperation() {
        // The payload's batched sub-field completes the root future off the dispatch thread, the
        // case where a release racing the last field's settle would roll the write back. The order
        // rests on graphql-java's future chain (the operation context settles, the execution
        // context completed after it releases), so it is pinned directly: the recording wrapper
        // sees the commit before the close, every time.
        for (int i = 0; i < 5; i++) {
            String title = "MUTATION-SCOPE-" + UUID.randomUUID();
            filmTitles.add(title);
            CONNECTION_EVENTS.clear();

            ExecutionResult result = execute("""
                mutation {
                    created: createFilm(in: { title: "%s", languageId: 1 }) {
                        filmId
                        actorsBySplitLookup(actor_id: [1]) { actorId }
                    }
                }
                """.formatted(title));

            assertThat(result.getErrors()).isEmpty();
            assertThat(CONNECTION_EVENTS).as("run %d", i).contains("commit", "close");
            assertThat(CONNECTION_EVENTS.indexOf("commit"))
                .as("run %d: the field committed before its connection was closed", i)
                .isLessThan(CONNECTION_EVENTS.lastIndexOf("close"));
            assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(title)))
                .as("run %d: the field reported as created is committed", i)
                .isEqualTo(1);
        }
    }

    @Test
    void aFieldThatTouchesNoDatabase_takesNoConnection() {
        int before = CONNECTIONS_OPENED.get();

        ExecutionResult result = execute("mutation { scopeNoDatabase(marker: \"x\") }");

        assertThat(result.getErrors()).isEmpty();
        assertThat(data(result).get("scopeNoDatabase")).isEqualTo("x");
        assertThat(CONNECTIONS_OPENED.get()).as("no connection, so no transaction").isEqualTo(before);
    }

    // ===== the engine must run mutations serially =====

    @Test
    void aNonSerialMutationStrategy_isRejectedBeforeAnyFieldRuns() {
        GraphQL parallel = runtime.newGraphQL(Graphitron.buildSchema(b -> {}))
            .mutationExecutionStrategy(new AsyncExecutionStrategy())
            .build();
        String never = marker();

        assertThatThrownBy(() -> parallel.execute(ownedInput(
                "mutation { scopeWriteOne(marker: \"" + never + "\") }").build()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("serial mutation strategy");
        assertThat(rows(never)).isZero();
    }

    // ===== helpers =====

    private String marker() {
        String marker = "MUTATION-SCOPE-" + UUID.randomUUID();
        markers.add(marker);
        return marker;
    }

    private int rows(String marker) {
        return dsl.fetchCount(DSL.table("mutation_scope_marker"), DSL.field("marker").eq(marker));
    }

    private ExecutionResult execute(String query) {
        return graphql.execute(ownedInput(query).build());
    }

    private static Map<String, Object> data(ExecutionResult result) {
        return result.getData();
    }

    private static List<List<Object>> errorPaths(ExecutionResult result) {
        return result.getErrors().stream().map(GraphQLError::getPath).toList();
    }

    /** A settle failure answers like any unmatched fetcher failure: a reference, never the cause. */
    private static void assertRedacted(GraphQLError error) {
        assertThat(error.getMessage())
            .startsWith("An error occurred. Reference: ")
            .doesNotContain("aborted", "violates", "25P02", "23503");
    }

    private static ExecutionInput.Builder ownedInput(String query) {
        return Graphitron.newOwnedExecutionInput(no.sikt.graphitron.sakila.example.app.SakilaSessions.of("{\"sub\":\"test-user\"}"), "{\"sub\":\"test-user\"}", "test-user").query(query);
    }

    /** The real connection, recording each transaction call made on it into {@link #CONNECTION_EVENTS}. */
    private static java.sql.Connection recording(java.sql.Connection real) {
        return (java.sql.Connection) Proxy.newProxyInstance(
            MutationScopeExecutionTest.class.getClassLoader(),
            new Class<?>[]{java.sql.Connection.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setSavepoint", "commit", "rollback", "close" -> CONNECTION_EVENTS.add(method.getName());
                    case "setAutoCommit" -> CONNECTION_EVENTS.add("setAutoCommit:" + args[0]);
                    default -> { }
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    /** A DataSource that hands out real connections and counts each acquisition. */
    private static DataSource countingDataSource() {
        return (DataSource) Proxy.newProxyInstance(
            MutationScopeExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    CONNECTIONS_OPENED.incrementAndGet();
                    return recording(SakilaDatabase.connect(jdbcUrl, jdbcUser, jdbcPassword));
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "countingDataSource";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
}
