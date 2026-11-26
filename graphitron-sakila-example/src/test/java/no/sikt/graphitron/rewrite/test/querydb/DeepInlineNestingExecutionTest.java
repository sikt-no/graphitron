package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.TableAliases;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An inline (non-{@code @splitQuery}) cycle of same-table children nested deep in one statement,
 * against a real database: every level reads its own parent's row up to the nesting bound, and a
 * selection past the bound is refused before any SQL runs. Fixture: {@code Film.sameRow:
 * FilmSameRow} and {@code FilmSameRow.film: Film}, both on {@code film} over its own primary key,
 * so every level of the cycle reads the root film's own row.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class DeepInlineNestingExecutionTest {

    @AutoClose
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger STATEMENT_COUNT = new AtomicInteger();

    /** The last of sakila's seeded film ids, 1 to 5; other execution tests write films of their own above it. */
    private static final int LAST_SEEDED_FILM = 5;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    STATEMENT_COUNT.incrementAndGet();
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    private ExecutionResult execute(String query) {
        return graphql.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build());
    }

    /**
     * {@code { films { filmId sameRow { filmId film { filmId sameRow { ... } } } } }}, {@code levels}
     * inline levels below the root, each selecting its film id.
     */
    private static String cycle(int levels) {
        var open = new StringBuilder("{ films { filmId ");
        var close = new StringBuilder(" } }");
        for (int level = 1; level <= levels; level++) {
            open.append(level % 2 == 1 ? "sameRow" : "film").append(" { filmId ");
            close.append(" }");
        }
        return open.append(close).toString();
    }

    @Test
    void everyLevelOfADeepInlineCycle_readsItsOwnParentsRow() {
        int levels = 28;
        var result = execute(cycle(levels));
        assertThat(result.getErrors()).isEmpty();
        var films = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("films");
        int judged = 0;
        for (var film : films) {
            int filmId = (Integer) film.get("filmId");
            if (filmId > LAST_SEEDED_FILM) {
                continue;
            }
            judged++;
            Map<String, Object> node = film;
            for (int level = 1; level <= levels; level++) {
                node = (Map<String, Object>) node.get(level % 2 == 1 ? "sameRow" : "film");
                assertThat(node).as("film %d at level %d", filmId, level).containsEntry("filmId", filmId);
            }
        }
        assertThat(judged).as("seeded films judged").isGreaterThan(1);
    }

    @Test
    void anInlineCyclePastTheDepthBound_isRefusedBeforeAnyStatementRuns() {
        STATEMENT_COUNT.set(0);
        var result = execute(cycle(TableAliases.MAX_DEPTH + 8));
        assertThat(STATEMENT_COUNT.get()).as("statements run").isZero();
        assertThat(result.getErrors()).singleElement()
            .satisfies(error -> assertThat(error.getMessage())
                .contains("more than " + TableAliases.MAX_DEPTH + " levels deep"));
    }

    @Test
    void anInlineCycleAtTheDepthBound_isServed() {
        var result = execute(cycle(TableAliases.MAX_DEPTH - 1));
        assertThat(result.getErrors()).isEmpty();
    }
}
