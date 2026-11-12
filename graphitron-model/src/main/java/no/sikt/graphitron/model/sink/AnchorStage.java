package no.sikt.graphitron.model.sink;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One stage of a gatherer's anchor phase: the name and class of its {@code meta_stage} row, and what
 * it runs.
 *
 * <p>A gatherer's anchor phase is its stages run in the order the roster states, so the order is
 * data a gate can read rather than a sequence of statements it would have to parse. What a stage
 * writes is stated beside it in {@code meta_stage_write}, which is what lets a gate refuse a stage
 * that reads a relation a later stage writes: it would read the previous reading's rows.
 *
 * @param name  the stage's {@code meta_stage} key
 * @param owner the class whose code the stage runs, its {@code meta_stage.stage_class}
 * @param body  what the stage runs, against one graph at the reading's instant
 */
public record AnchorStage(String name, Class<?> owner, Body body) {

    /** What one stage does to one graph, at the reading's instant. */
    @FunctionalInterface
    public interface Body {
        void run(DSLContext dsl, String graph, LocalDateTime readAt);
    }

    /**
     * Runs {@code stages} in order, each named to the writer's {@link WriteTimes} while it runs, so
     * {@code store_graph_write_stage} says which stage wrote what.
     */
    public static void runAll(DSLContext dsl, String graph, LocalDateTime readAt, List<AnchorStage> stages) {
        try {
            for (var stage : stages) {
                WriteTimes.stage(dsl, stage.name());
                stage.body().run(dsl, graph, readAt);
            }
        } finally {
            WriteTimes.stage(dsl, null);
        }
    }
}
