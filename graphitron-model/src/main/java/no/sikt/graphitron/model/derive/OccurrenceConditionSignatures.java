package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_condition_signature}, what each field occurrence's condition
 * method takes beside the table and the arguments, from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, run once {@link OccurrenceConditions}' conditions and fills
 * have written and swept, so it reads this reading's rows alone.
 */
public final class OccurrenceConditionSignatures {

    private OccurrenceConditionSignatures() {}

    /** States the graph's condition signatures, then deletes those this reading did not mark. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var s = GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
        var r = GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE_RULE;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.BOUND, s.TAKES_CONTEXT, s.TAKES_SESSION, s.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.TAKES_CONTEXT, r.TAKES_SESSION,
                    val(touchedAt, s.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(s.GRAPH_NAME, s.COORDINATE, s.BOUND)
            .doUpdate()
            .set(s.TAKES_CONTEXT, excluded(s.TAKES_CONTEXT))
            .set(s.TAKES_SESSION, excluded(s.TAKES_SESSION))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(s).where(s.GRAPH_NAME.eq(graph)).and(s.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
