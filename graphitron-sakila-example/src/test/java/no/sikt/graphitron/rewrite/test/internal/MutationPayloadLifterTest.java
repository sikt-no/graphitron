package no.sikt.graphitron.rewrite.test.internal;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end execution test for the {@code @sourceRow} directive.
 *
 * <p>The fixture shape lives in {@code graphitron-sakila-service}: a Java-record payload
 * ({@code CreateFilmPayload(Integer languageId)}), a static lifter
 * ({@code CreateFilmPayloadLifter.liftLanguageId}), and a hand-rolled service that returns
 * three deterministic payloads with {@code languageId}s {@code [1, 2, 1]}. The schema wires
 * these via {@code Query.recentlyCreatedFilms: [CreateFilmPayload!]!} (root {@code @service})
 * and {@code CreateFilmPayload.language: [Language!]!} (child {@code @sourceRow}).
 *
 * <p>The test asserts:
 * <ul>
 *   <li>One JDBC round-trip total — the service hand-rolls payloads (no DB hit on the root),
 *       so the only query is the lifter-driven batched language lookup.</li>
 *   <li>Two distinct payloads with the same {@code languageId} resolve to the same
 *       {@code Language} row.</li>
 * </ul>
 */
@ExecutionTier
class MutationPayloadLifterTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                }
            }));

        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    @Test
    void lifterPayloads_dataLoaderBatchesByLifterKey() {
        // Three CreateFilmPayloads, languageIds [1, 2, 1]; the DataLoader dispatches once.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ recentlyCreatedFilms { languageId language { languageId name } } }");

        // The service hand-rolls payloads → no root JDBC hit. Lifter-driven DataLoader runs
        // exactly one batched language lookup. Unbatched scatter would fire 3 queries; an
        // empty-input short-circuit would fire 0.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);

        List<Map<String, Object>> payloads = (List<Map<String, Object>>) data.get("recentlyCreatedFilms");
        assertThat(payloads).hasSize(3);
        assertThat(payloads).extracting(p -> p.get("languageId"))
            .containsExactly(1, 2, 1);

        // Per-parent Language list, narrowed by language_id. Each parent gets a single-element
        // list; the two payloads with languageId=1 both resolve to language "English".
        var lang0 = (List<Map<String, Object>>) payloads.get(0).get("language");
        var lang1 = (List<Map<String, Object>>) payloads.get(1).get("language");
        var lang2 = (List<Map<String, Object>>) payloads.get(2).get("language");

        // language.name is CHAR(20) → space-padded; trim before comparing.
        assertThat(lang0).extracting(l -> l.get("languageId")).containsExactly(1);
        assertThat(lang0).extracting(l -> ((String) l.get("name")).trim()).containsExactly("English");
        assertThat(lang1).extracting(l -> l.get("languageId")).containsExactly(2);
        assertThat(lang1).extracting(l -> ((String) l.get("name")).trim()).containsExactly("Italian");
        assertThat(lang2).extracting(l -> l.get("languageId")).containsExactly(1);
        assertThat(lang2).extracting(l -> ((String) l.get("name")).trim()).containsExactly("English");
    }
}
