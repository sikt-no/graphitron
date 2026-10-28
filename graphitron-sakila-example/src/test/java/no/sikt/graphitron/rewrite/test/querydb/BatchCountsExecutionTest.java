package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.generated.util.ConnectionResult;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Execution-tier coverage of the generated {@code ConnectionResult.BatchCounts}, the per-batch
 * memo both batched connection producers bind behind {@code totalCount}. The GraphQL-level tests
 * in {@link GraphQLQueryTest} pin that a batch costs one grouped count; these pin the two cases no
 * batched fixture reaches: a parent with no rows, and a grouped statement that fails.
 */
@ExecutionTier
class BatchCountsExecutionTest {

    static DSLContext dsl;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                }
            }));
    }


    /** {@code film_actor} rows of films 1 to 3 keyed by batch index {@code film_id - 1}. */
    private static Table<?> filmActorsByIndex() {
        Field<Integer> filmId = DSL.field(DSL.name("film_id"), Integer.class);
        return dsl.select(filmId.minus(1).as("__idx__"))
            .from(DSL.table(DSL.name("film_actor")))
            .where(filmId.le(3))
            .asTable("countSource");
    }

    @Test
    void slotsShareOneGroupedCount_andAnIndexWithoutRowsCountsZero() {
        // Seed: film 1 -> {1, 2}, film 2 -> {1, 3}, film 3 -> {1}. keyCount 4 adds a parent the
        // grouped count returns no row for.
        var source = filmActorsByIndex();
        var counts = new ConnectionResult.BatchCounts(source, source.field("__idx__", Integer.class), 4);

        QUERY_COUNT.set(0);
        assertThat(counts.slot(0).count(dsl)).isEqualTo(2);
        assertThat(counts.slot(1).count(dsl)).isEqualTo(2);
        assertThat(counts.slot(2).count(dsl)).isEqualTo(1);
        assertThat(counts.slot(3).count(dsl)).as("an index with no group row").isEqualTo(0);
        assertThat(QUERY_COUNT.get()).as("one grouped statement for every slot").isEqualTo(1);
    }

    @Test
    void aFailedGroupedCountIsRethrownByEverySlotWithoutRerunning() {
        Field<Integer> idx = DSL.field(DSL.name("__idx__"), Integer.class);
        var source = dsl.select(DSL.inline(0).as("__idx__"))
            .from(DSL.table(DSL.name("no_such_table")))
            .asTable("countSource");
        var counts = new ConnectionResult.BatchCounts(source, source.field(idx), 2);

        QUERY_COUNT.set(0);
        Throwable first = catchThrowable(() -> counts.slot(0).count(dsl));
        Throwable second = catchThrowable(() -> counts.slot(1).count(dsl));
        assertThat(first).isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(second).as("the kept failure, not a fresh one").isSameAs(first);
        assertThat(QUERY_COUNT.get()).as("the failing statement ran once").isEqualTo(1);
    }
}
