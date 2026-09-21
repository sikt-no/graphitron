package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ACCESSOR_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code intent_type_backing_class}: which Java class backs each of
 * a graph's types, derived as a transitive closure from the producer-grounded seeds over
 * {@code intent_field_accessor_hop}'s edges. Runs inside capture's own transaction after the
 * flush, clears the run's graph partition first (the cadence doctrine's clearing rule), and
 * re-derives, so on any settled store the relation is current for every captured graph. The
 * relation is a table rather than a view for the classification domain's reason exactly: the closure
 * is over the SDL type graph, which is cyclic, and H2 has no safe recursive view form for one.
 *
 * <p>The rule is the relation's, not this class's. Every statement below is joins over relations
 * that state their own contracts, so what lives here is the loop and its termination. The seeds are
 * {@code intent_type_backing_seed}, which states both axes and is read whole, and the frontier is
 * every backed type's fields read off its class. Both ends are narrowed to objects and input
 * objects, which is where a class can stand for a type at all, and the two axes share one frontier:
 * an input object seeded from a parameter has its own fields read off that class exactly as an
 * output type does.
 *
 * <p>The one closure condition is an authored fact: a coordinate the author applied a producer
 * directive to, {@code @service} or {@code @externalField}, is not read off its parent, its value
 * coming from the method the author named. It is read where it is captured, off the two entry
 * relations {@code graphitron_service_entry} and {@code graphitron_external_field_entry}, which
 * hold one row per application whatever the application spelled. Not off the reference those
 * entries decode to, which drops an application missing its method, and not off that reference's
 * census resolution, which drops an application naming a class no classpath entry declared: both
 * are the author saying the value does not come from the member, and the classification walk skips
 * the field on the applied directive alone. The condition is broader than the walk's on one arm,
 * the walk skipping on {@code @service} only; that breadth predates the entry read and is the
 * derivation's own reading, an external field's method being invoked for the field like a
 * service's.
 *
 * <p>The condition used to be read off the resolution, and that was wrong before it was slow: an
 * unreached producer backed its type off the parent's member, a wrong class dressed as an answer.
 * The slowness was the symptom the fact model predicts of a reader pointed past a captured fact.
 * The resolution carries a window function, which no outside predicate prunes, so the anti-join
 * evaluated the whole census join once per hop the closure reached and its cost tracked the census
 * rather than the graph; against the entry tables the same anti-join is a primary-key seek into
 * each and measured within a few percent of the statement with no producer condition at all, where
 * the resolved form cost minutes per capture on a consumer census.
 *
 * <p>The pass count is bounded by the row count rather than by a constant. Every pass inserts at
 * least one pair the relation did not hold, so a pass index above the current row count is
 * impossible while the frontier stays monotone, and the guard turns a non-monotone edit into a
 * failure instead of a build that hangs.
 */
public final class TypeBackingRows {

    private TypeBackingRows() {}

    /** Clears and re-derives the graph's backing partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(INTENT_TYPE_BACKING_CLASS)
            .where(INTENT_TYPE_BACKING_CLASS.GRAPH_NAME.eq(graphName))
            .execute();
        int rows = seed(dsl, graphName);
        for (int pass = 1; ; pass++) {
            int added = expand(dsl, graphName);
            if (added == 0) {
                return;
            }
            rows += added;
            if (pass > rows) {
                throw new IllegalStateException(
                    "intent_type_backing_class closure for graph '" + graphName + "' ran " + pass
                        + " passes over " + rows + " rows; the frontier statement has stopped"
                        + " being monotone");
            }
        }
    }

    /**
     * The seeds, read from the relation that states them. Both axes are arms of
     * {@code intent_type_backing_seed}, so what a producer grounds is said once, where the reader
     * that needs to tell a grounding from a hop can see it too, rather than twice in a writer.
     */
    private static int seed(DSLContext dsl, String graphName) {
        var b = INTENT_TYPE_BACKING_CLASS;
        var s = INTENT_TYPE_BACKING_SEED;
        return dsl.insertInto(b, b.GRAPH_NAME, b.TYPE_NAME, b.CLASS_NAME)
            .select(select(s.GRAPH_NAME, s.TYPE_NAME, s.CLASS_NAME)
                .from(s)
                .where(s.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * One frontier pass: every field of a backed type, read off the class backing it, backs its
     * own named type with what the hop lands on. A coordinate the author applied a producer
     * directive to is skipped, its value coming from that method rather than from the parent's
     * member; the class javadoc says why the entries and not their resolution.
     */
    private static int expand(DSLContext dsl, String graphName) {
        var b = INTENT_TYPE_BACKING_CLASS;
        var backed = INTENT_TYPE_BACKING_CLASS.as("backed");
        var h = INTENT_FIELD_ACCESSOR_HOP;
        var service = GRAPHITRON_SERVICE_ENTRY;
        var external = GRAPHITRON_EXTERNAL_FIELD_ENTRY;
        return dsl.insertInto(b, b.GRAPH_NAME, b.TYPE_NAME, b.CLASS_NAME)
            .select(selectDistinct(val(graphName), GRAPHQL_FIELD.NAMED_TYPE, h.TO_CLASS_NAME)
                .from(backed)
                .join(h).on(h.GRAPH_NAME.eq(backed.GRAPH_NAME)
                    .and(h.TYPE_NAME.eq(backed.TYPE_NAME))
                    .and(h.FROM_CLASS_NAME.eq(backed.CLASS_NAME)))
                .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(h.GRAPH_NAME)
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq(h.TYPE_NAME))
                    .and(GRAPHQL_FIELD.FIELD_NAME.eq(h.FIELD_NAME)))
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(h.GRAPH_NAME)
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.NAMED_TYPE))
                    .and(GRAPHQL_TYPE.KIND.in("OBJECT", "INPUT_OBJECT")))
                .where(backed.GRAPH_NAME.eq(graphName))
                .and(notExists(selectOne().from(service)
                    .where(service.GRAPH_NAME.eq(h.GRAPH_NAME))
                    .and(service.TYPE_NAME.eq(h.TYPE_NAME))
                    .and(service.FIELD_NAME.eq(h.FIELD_NAME))))
                .and(notExists(selectOne().from(external)
                    .where(external.GRAPH_NAME.eq(h.GRAPH_NAME))
                    .and(external.TYPE_NAME.eq(h.TYPE_NAME))
                    .and(external.FIELD_NAME.eq(h.FIELD_NAME))))
                .and(notExists(selectOne().from(b)
                    .where(b.GRAPH_NAME.eq(graphName))
                    .and(b.TYPE_NAME.eq(GRAPHQL_FIELD.NAMED_TYPE))
                    .and(b.CLASS_NAME.eq(h.TO_CLASS_NAME)))))
            .execute();
    }
}
