package no.sikt.graphitron.rewrite.generated;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.DataFetchingFieldSelectionSet;
import no.sikt.graphitron.generated.facts.connection_producers.Graphitron;
import no.sikt.graphitron.generated.facts.connection_producers.fetchers.FilmFetchers;
import no.sikt.graphitron.generated.facts.connection_producers.fetchers.QueryFetchers;
import no.sikt.graphitron.generated.facts.connection_producers.util.ConnectionHelper;
import no.sikt.graphitron.generated.facts.connection_producers.util.Connections;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.ACTOR;
import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection producers of the fact scenario {@code connection-producers.graphqls}, called
 * directly against the shared database: the root producer and the batched one each return the
 * generated connection class its type names, whose edges carry cursors that decode back to the
 * element's key, whose page info is bounded by its first and last element, and whose count runs
 * only when read. The batched producer's parents share one count statement. Every read is over
 * seeded rows: films 1 to 3, and their actors {1, 2}, {1, 3} and {1}.
 */
@ExecutionTier
class ConnectionProducersExecutionTest {

    /** A context over the shared database that counts the statements it runs. */
    private static DSLContext counting(AtomicInteger statements) {
        return TestDatabase.dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    statements.incrementAndGet();
                }
            }))
            .dsl();
    }

    /** A selection with no field under it: the page still selects its order columns. */
    private static DataFetchingFieldSelectionSet nothingSelected() {
        return (DataFetchingFieldSelectionSet) Proxy.newProxyInstance(
            DataFetchingFieldSelectionSet.class.getClassLoader(), new Class<?>[] {DataFetchingFieldSelectionSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getImmediateFields", "getFields" -> List.of();
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static DataFetchingEnvironment env(DSLContext dsl, Map<String, Object> arguments) {
        var input = Graphitron.newExecutionInput(dsl).query("{ films { totalCount } }").build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(input.getGraphQLContext())
            .dataLoaderRegistry(input.getDataLoaderRegistry())
            .arguments(arguments)
            .selectionSet(nothingSelected())
            .build();
    }

    private static int keyOf(String cursor, Field<Integer> column) {
        Field<?>[] seek = ConnectionHelper.decodeCursor(cursor, List.of(column));
        return (Integer) ((Param<?>) seek[0]).getValue();
    }

    @Test
    void theRootProducerBuildsItsConnection_andCountsOnlyWhenRead() {
        var statements = new AtomicInteger();
        var dsl = counting(statements);
        var arguments = new HashMap<String, Object>();
        arguments.put("filmIds", List.of(1, 2, 3));
        arguments.put("first", 2);
        var env = env(dsl, arguments);

        Connections.FilmsConnection page = QueryFetchers.rowsFilms(dsl, env);
        assertThat(statements).as("the page is one statement").hasValue(1);

        var edges = page.edges();
        assertThat(edges).hasSize(2);
        assertThat(edges.stream().map(e -> keyOf(e.cursor(), FILM.FILM_ID)).toList()).containsExactly(1, 2);
        assertThat(edges.stream().map(e -> e.node().get("film_id", Integer.class)).toList()).containsExactly(1, 2);
        assertThat(page.nodes()).extracting(r -> r.get("film_id", Integer.class)).containsExactly(1, 2);
        var info = page.pageInfo();
        assertThat(info.hasNextPage()).isTrue();
        assertThat(info.hasPreviousPage()).isFalse();
        assertThat(info.startCursor()).isEqualTo(edges.get(0).cursor());
        assertThat(info.endCursor()).isEqualTo(edges.get(1).cursor());
        assertThat(statements).as("nothing read so far runs a statement").hasValue(1);

        assertThat(page.totalCount(env)).isEqualTo(3);
        assertThat(statements).as("reading the count runs it once").hasValue(2);

        // The next page, after the last cursor: film 3 alone, with a page behind it.
        arguments.put("after", info.endCursor());
        Connections.FilmsConnection next = QueryFetchers.rowsFilms(dsl, env(dsl, arguments));
        assertThat(next.nodes()).extracting(r -> r.get("film_id", Integer.class)).containsExactly(3);
        assertThat(next.pageInfo().hasNextPage()).isFalse();
        assertThat(next.pageInfo().hasPreviousPage()).isTrue();
    }

    @Test
    void theBatchedProducerBuildsOneConnectionPerParent_andCountsTheBatchInOneStatement() {
        var statements = new AtomicInteger();
        var dsl = counting(statements);
        var env = env(dsl, Map.of("first", 1));

        List<Connections.ActorsConnection> pages = FilmFetchers.rowsActors(
            List.of(DSL.row(1), DSL.row(2), DSL.row(3)), env);
        assertThat(statements).as("the batch's pages are one statement").hasValue(1);

        assertThat(pages).hasSize(3);
        assertThat(pages.stream().map(p -> p.edges().stream()
                .map(e -> keyOf(e.cursor(), ACTOR.ACTOR_ID)).toList()).toList())
            .containsExactly(List.of(1), List.of(1), List.of(1));
        assertThat(pages.stream().map(p -> p.pageInfo().hasNextPage()).toList())
            .containsExactly(true, true, false);
        assertThat(statements).as("nothing read so far runs a statement").hasValue(1);

        assertThat(pages.stream().map(p -> p.totalCount(env)).toList()).containsExactly(2, 2, 1);
        assertThat(statements).as("every parent's count is one grouped statement").hasValue(2);
    }
}
