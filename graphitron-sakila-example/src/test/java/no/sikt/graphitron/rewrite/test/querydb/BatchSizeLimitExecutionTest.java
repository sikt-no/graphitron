package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.BatchSizeLimit;
import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage of {@link BatchSizeLimit}, the consumer hook that bounds how many keys
 * one batched query carries. Without it a batch is as large as the request makes it; with it a
 * batch over the limit runs as several statements of at most that many keys, and each parent
 * still gets its own rows.
 *
 * <p>Driven against {@code Film.actorsByCondition}, a list-valued {@code @splitQuery} child, under
 * the five seeded films, which no test deletes. Statements are counted where every path sends
 * them, at the JDBC connection, and never read: a request's count with no limit is the baseline,
 * and a limit of two over five parents adds the two statements the second and third chunk cost.
 * The failure case runs {@code Film.databaseError}, the batched child {@code @service} whose
 * statement always fails, so each chunk fails on its own.
 */
@ExecutionTier
class BatchSizeLimitExecutionTest {

    private static final Pattern REFERENCE = Pattern.compile("An error occurred\\. Reference: ([0-9a-f-]{36})\\.");

    private static final String FIVE_FILMS = "filmById(film_id: [\"1\", \"2\", \"3\", \"4\", \"5\"])";

    private static final String ACTORS_OF_FIVE_FILMS = "{ " + FIVE_FILMS + " { filmId actorsByCondition { actorId } } }";

    private static final String DATABASE_ERROR_OF_FIVE_FILMS = "{ " + FIVE_FILMS + " { databaseError } }";

    /** How many statements the test's connections have prepared since the count was last reset. */
    static final AtomicInteger STATEMENTS = new AtomicInteger();

    static DataSource dataSource;

    /** What the query answers with no limit, which a limit must not change. */
    static Map<String, Object> unbounded;

    /** The statements the owned-connection runtime prepares for the query with no limit. */
    static int unboundedStatements;

    @BeforeAll
    static void startDatabase() {
        String url = SakilaDatabase.url();
        String user = SakilaDatabase.user();
        String password = SakilaDatabase.password();
        dataSource = (DataSource) Proxy.newProxyInstance(
            BatchSizeLimitExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> recording(SakilaDatabase.connect(url, user, password));
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "batchSizeLimitDataSource";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        STATEMENTS.set(0);
        var result = run(runtime(), ACTORS_OF_FIVE_FILMS);
        assertThat(result.getErrors()).isEmpty();
        unbounded = result.getData();
        unboundedStatements = STATEMENTS.get();
    }

    /** {@code connection}, counting each statement prepared on it. */
    private static Connection recording(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
            BatchSizeLimitExecutionTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")) {
                    STATEMENTS.incrementAndGet();
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    @BeforeEach
    void forgetEarlierStatements() {
        STATEMENTS.set(0);
    }

    @Test
    void withoutALimit_theRequestCostsTheSameStatementsEveryTime() {
        var result = run(runtime(), ACTORS_OF_FIVE_FILMS);

        assertThat(result.getErrors()).isEmpty();
        assertThat(STATEMENTS.get()).as("the baseline the other cases count against").isEqualTo(unboundedStatements);
    }

    @Test
    void aLimitOfTwo_runsFiveParentsAsThreeStatements_andEachParentStillGetsItsRows() {
        var result = run(runtime().withBatchSizeLimit(env -> 2), ACTORS_OF_FIVE_FILMS);

        assertThat(result.getErrors()).isEmpty();
        assertThat(STATEMENTS.get())
            .as("ceil(5 / 2) child statements of at most two keys where there was one")
            .isEqualTo(unboundedStatements + 2);
        assertThat(result.<Map<String, Object>>getData()).isEqualTo(unbounded);
    }

    @Test
    void theLimitIsAskedOncePerLoader_withTheEnvironmentOfAFieldThatNeededIt() {
        var askedAt = new CopyOnWriteArrayList<String>();
        BatchSizeLimit limit = env -> {
            askedAt.add(env.getExecutionStepInfo().getPath().toString());
            return 2;
        };

        var result = run(runtime().withBatchSizeLimit(limit),
            "{ " + FIVE_FILMS + " { filmId actorsByCondition { actorId } actorsViaJunctionCondition { actorId } } }");

        assertThat(result.getErrors()).isEmpty();
        assertThat(askedAt).as("two loaders, each asked once however many films need it").hasSize(2);
        assertThat(askedAt).filteredOn(path -> path.matches("/filmById\\[[0-4]]/actorsByCondition")).hasSize(1);
        assertThat(askedAt).filteredOn(path -> path.matches("/filmById\\[[0-4]]/actorsViaJunctionCondition")).hasSize(1);
    }

    @Test
    void aLimitBelowOne_isNoLimit() {
        var result = run(runtime().withBatchSizeLimit(env -> 0), ACTORS_OF_FIVE_FILMS);

        assertThat(result.getErrors()).isEmpty();
        assertThat(STATEMENTS.get()).isEqualTo(unboundedStatements);
        assertThat(result.<Map<String, Object>>getData()).isEqualTo(unbounded);
    }

    @Test
    void aLimitThatThrows_leavesTheBatchUnbounded_andIsOneErrorLineNamingTheHook() {
        // A class of its own, so its name finds this request's line whatever else is logging.
        final class BrokenLimit implements BatchSizeLimit {
            @Override
            public int maxBatchSize(DataFetchingEnvironment env) {
                throw new IllegalStateException("the consumer's limit is broken");
            }
        }
        try (var log = ErrorRouterLog.capture()) {
            var result = run(runtime().withBatchSizeLimit(new BrokenLimit()), ACTORS_OF_FIVE_FILMS);

            assertThat(result.getErrors()).isEmpty();
            assertThat(STATEMENTS.get()).isEqualTo(unboundedStatements);
            assertThat(result.<Map<String, Object>>getData()).isEqualTo(unbounded);
            assertThat(log.errorsMentioning(BrokenLimit.class.getName())).singleElement().satisfies(record -> {
                assertThat(record.getThrown()).isNull();
                assertThat(record.getMessage())
                    .startsWith("BatchSizeLimit " + BrokenLimit.class.getName()
                        + " threw java.lang.IllegalStateException; path = /filmById[")
                    .endsWith("]/actorsByCondition")
                    .doesNotContain("the consumer's limit is broken");
            });
        }
    }

    @Test
    void theEscapeHatchEngine_readsTheLimitFromTheRequestsContext() {
        var engine = Graphitron.newGraphQL().build();
        var dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        var unlimited = engine.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query(ACTORS_OF_FIVE_FILMS).build());
        assertThat(unlimited.getErrors()).isEmpty();
        int unlimitedStatements = STATEMENTS.getAndSet(0);

        BatchSizeLimit limit = env -> 2;
        var result = engine.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query(ACTORS_OF_FIVE_FILMS)
            .graphQLContext(b -> b.put(BatchSizeLimit.class, limit))
            .build());

        assertThat(result.getErrors()).isEmpty();
        assertThat(STATEMENTS.get()).isEqualTo(unlimitedStatements + 2);
        assertThat(result.<Map<String, Object>>getData()).isEqualTo(unbounded);
    }

    @Test
    void underALimit_aBatchThatFails_isOneRecordPerFailedChunk_andEachChunksFieldsQuoteItsReference() {
        var whole = run(runtime(), DATABASE_ERROR_OF_FIVE_FILMS);
        assertThat(whole.getErrors()).hasSize(5);
        assertThat(whole.getErrors().stream().map(BatchSizeLimitExecutionTest::referenceOf).distinct())
            .as("with no limit the batch fails as one").hasSize(1);
        int wholeStatements = STATEMENTS.getAndSet(0);

        try (var log = ErrorRouterLog.capture()) {
            var result = run(runtime().withBatchSizeLimit(env -> 2), DATABASE_ERROR_OF_FIVE_FILMS);

            assertThat(result.getErrors()).hasSize(5);
            assertThat(STATEMENTS.get()).as("each chunk runs, and fails, on its own").isEqualTo(wholeStatements + 2);
            Map<String, Long> fieldsPerReference = result.getErrors().stream()
                .collect(Collectors.groupingBy(BatchSizeLimitExecutionTest::referenceOf, Collectors.counting()));
            assertThat(fieldsPerReference.values()).as("fields per failed chunk").containsExactlyInAnyOrder(2L, 2L, 1L);
            assertThat(fieldsPerReference.keySet()).allSatisfy(reference ->
                assertThat(log.errorsMentioning(reference)).as("records for chunk %s", reference).hasSize(1));
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

    private static String referenceOf(GraphQLError error) {
        Matcher matcher = REFERENCE.matcher(error.getMessage());
        assertThat(matcher.matches()).as("a redacted error: %s", error.getMessage()).isTrue();
        return matcher.group(1);
    }
}
