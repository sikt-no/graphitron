package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

/**
 * The {@code occurrence-depth-defects} stage: the defects judging what {@link OccurrenceDepths} and
 * {@link OccurrenceBuilds} write, a child producer's lift and the key it takes, and the column a
 * field reads or writes, from their rule view. A stage of their own, so neither the depth loop nor
 * the gatherer's last rule carries them.
 */
public final class OccurrenceDepthDefects {

    private OccurrenceDepthDefects() {}

    /** Writes the stage's defects, marked with this reading. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt) {
        EntryDefects.derive(dsl, graph, readAt, EntryDefects.DEPTHS);
    }
}
