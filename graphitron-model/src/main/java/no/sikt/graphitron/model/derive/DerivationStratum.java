package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.sink.Progress;
import graphql.schema.GraphQLSchema;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The derivation gatherer's stages as data: every step the capture pass runs after the
 * gatherers have flushed, in the one order they may run, and what each writes.
 *
 * <p>A step is a stage or a producer. A stage is one {@code INSERT} over the stored view stating its
 * rule, or a jOOQ statement where the rule is a walk; a producer is hand-written jOOQ whose rule no
 * view could state. Both reconcile their own partition, clearing it and deriving it again, which is
 * what lets a second capture of one graph be the ordinary case.
 *
 * <p><b>The order is the one invariant here: no step reads a table a later step writes</b>, because
 * it would read the previous capture's rows. Statement order in a list is a hand-kept ordering, and
 * what keeps it from being one with no derivable source is {@code StageOrderGateTest}: it parses each
 * stage's rule for what it reads and fails the build on a step placed ahead of what it depends on.
 * The write sets below are the other half of that check, declared because a producer's reads are
 * jOOQ code no stored definition exposes.
 *
 * <p>The steps run inside one transaction, so no reader ever observes an emptied partition.
 */
public final class DerivationStratum {

    /**
     * One step: its name, the base tables it writes, and the statements that write them.
     *
     * @param writes the tables the step empties and refills for a graph, lower case as declared
     */
    public record Step(String name, Set<String> writes, Body body) {
        public Step {
            writes = Set.copyOf(writes);
        }
    }

    /** What a step does to one graph's partitions. */
    @FunctionalInterface
    public interface Body {
        void run(DSLContext dsl, String graphName);
    }

    private DerivationStratum() {}

    /**
     * The steps in the order they run.
     *
     * @param schema the corpus as it assembled, for the one producer that reads an executable schema
     *               rather than rows; null where the assembly was rejected, which it states
     */
    public static List<Step> steps(GraphQLSchema schema) {
        return List.of(
            new Step("ClassificationDomainCapture", Set.of("intent_type_domain"),
                (dsl, graph) -> ClassificationDomainCapture.derive(dsl, graph, schema)));
    }

    /** Runs the stages for one graph inside one transaction, reporting to {@code progress}. */
    public static void run(DSLContext dsl, String graphName, GraphQLSchema schema,
                           StageProgress progress) {
        List<Step> steps = steps(schema);
        Progress.started(dsl, graphName, GATHERER);
        dsl.transaction(tx -> runInOne(tx.dsl(), graphName, steps, progress));
        Progress.completed(dsl, graphName, GATHERER);
    }

    /** This gatherer's row in {@code store_graph_progress}. */
    private static final String GATHERER = "derivation";

    /**
     * Every step on the caller's {@link DSLContext}, which is how a capture keeps the stages inside
     * one transaction: no reader observes an emptied partition.
     */
    public static void runInOne(DSLContext dsl, String graphName, List<Step> steps,
                                StageProgress progress) {
        progress.observe(new StageProgress.Event.PassStarted(steps.size(), graphName));
        long startedAt = System.nanoTime();
        for (int position = 0; position < steps.size(); position++) {
            runOne(dsl, graphName, steps, position, progress);
        }
        progress.observe(new StageProgress.Event.PassFinished(System.nanoTime() - startedAt));
    }

    private static void runOne(DSLContext dsl, String graphName, List<Step> steps, int position,
                               StageProgress progress) {
        Step step = steps.get(position);
        // Named before a statement is issued, so a step that never returns has already said which
        // it is; StageProgress states why that order is the instrument.
        progress.observe(new StageProgress.Event.StageStarted(step, position + 1, steps.size()));
        long startedAt = System.nanoTime();
        step.body().run(dsl, graphName);
        long nanos = System.nanoTime() - startedAt;
        int rows = step.writes().stream()
            .mapToInt(written -> dsl.select(count())
                .from(table(relation(written)))
                .where(field(name("GRAPH_NAME"), String.class).eq(graphName))
                .fetchOne(0, int.class))
            .sum();
        progress.observe(new StageProgress.Event.StageFinished(step, nanos, rows));
    }

    /** Relation names are declared lower case; H2's catalog holds the folded spelling. */
    private static org.jooq.Name relation(String relationName) {
        return name(relationName.toUpperCase(Locale.ROOT));
    }
}
