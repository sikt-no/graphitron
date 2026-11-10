package no.sikt.graphitron.emit;


import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link RoutineWriteQuery#read} costs the store, counted rather than reasoned about. Three
 * statements at three grains, and three however many coordinates the graph holds, however many
 * hops their paths cross and however many arguments their routines take.
 *
 * <p>An enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail for being
 * slow. The shape it pins is invisible from every other gate. A fan-out into a statement per
 * coordinate returns exactly the rows one statement does, so {@link RoutineWriteQueryTest} stays
 * green, the emitted output is identical, and the compile and execution tiers see nothing. A reader
 * adding a fact to a row will reach for another query keyed by the coordinate, which is the
 * natural move and the one this test refuses.
 *
 * <p>The query is the emission's only store read. The two facts still taken from the classified
 * schema (the error channel and the tenancy) are read where the emitter is called, which holds no
 * store handle, so they cost the store nothing by construction.
 */
@PipelineTier
class RoutineWriteQueryStatementCountTest {

    /**
     * One per grain: the occurrence, a records result's step pairing, an object result's captured
     * pairing. Three rather than four since admission is read off the occurrence statement's own
     * facts instead of a statement of its own ahead of it.
     */
    private static final int GRAINS = 3;

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void aWiderCorpusCostsTheSame() {
        // The assertion the pin exists for. Three admitted coordinates, both result shapes, a
        // two-hop path beside a one-hop path: every one of those is a row count, and none of them is
        // a statement count, which the graph with none below costs too.
        var counted = new AtomicInteger();
        var rows = FACTS.read("scenarios/routine-write",
            graph -> RoutineWriteQuery.read(counting(graph.handle(), counted)));
        assertThat(rows.rows())
            .as("the wide corpus really is wider; an equality between two empty reads would hold"
                + " for the wrong reason")
            .hasSize(3);
        assertThat(counted.get()).isEqualTo(GRAINS);
    }

    @Test
    void aGraphWithNoRoutineWriteCostsTheSame() {
        // Absence is an answer, and it is the same three statements: nothing here may probe a
        // relation at a time to discover that none of them holds a row, and nothing may skip a
        // grain because an earlier one came back empty. Either shape makes the count a function of
        // the corpus in the direction the pin is blind to reading as a saving.
        assertThat(statementsToProduce("scenarios/producer-claim")).isEqualTo(GRAINS);
    }

    /** The statements one {@link RoutineWriteQuery#read} executes over {@code path}'s graph. */
    private static int statementsToProduce(String path) {
        var counted = new AtomicInteger();
        FACTS.read(path, graph -> RoutineWriteQuery.read(counting(graph.handle(), counted)));
        return counted.get();
    }

    /** {@code store} seen through a handle that counts the statements it executes. */
    private static StoreHandle counting(StoreHandle store, AtomicInteger counted) {
        var configuration = store.dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    counted.incrementAndGet();
                }
            }));
        return new StoreHandle(DSL.using(configuration), store.graphName());
    }
}
