package no.sikt.graphitron.rewrite.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_DECLARED_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_ACCESSOR_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code intent_type_backing_class}: which Java class backs each of
 * a graph's types, derived as a transitive closure from the producer-grounded seeds over
 * {@code intent_field_accessor_hop}'s edges. Runs inside capture's own transaction after the
 * flush, clears the run's graph partition first (the cadence doctrine's clearing rule), and
 * re-derives, so on any settled store the relation is current for every captured graph. The
 * relation is a table rather than a view for {@link ReachabilityRows}' reason exactly: the closure
 * is over the SDL type graph, which is cyclic, and H2 has no safe recursive view form for one.
 *
 * <p>The rule is the relation's, not this class's. Both statements below are joins over relations
 * that state their own contracts, so what lives here is the loop and its termination: the seeds
 * are {@code intent_field_producer_method} read at the class its method delivers, the frontier is
 * every backed type's fields read off its class, and both ends are narrowed to objects and input
 * objects, which is where a class can stand for a type at all.
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
     * The seeds: a field with an authored Java reference backs the type it returns with the class
     * the resolved method delivers. A reference matching several overloads seeds each of them,
     * this relation stating ambiguity as rows on the terms the producer relation already set.
     */
    private static int seed(DSLContext dsl, String graphName) {
        var b = INTENT_TYPE_BACKING_CLASS;
        var p = INTENT_FIELD_PRODUCER_METHOD;
        var e = INTENT_DECLARED_TYPE_ELEMENT;
        return dsl.insertInto(b, b.GRAPH_NAME, b.TYPE_NAME, b.CLASS_NAME)
            .select(selectDistinct(p.GRAPH_NAME, GRAPHQL_FIELD.NAMED_TYPE, e.ELEMENT_CLASS)
                .from(p)
                .join(GRAPHQL_FIELD).on(GRAPHQL_FIELD.GRAPH_NAME.eq(p.GRAPH_NAME)
                    .and(GRAPHQL_FIELD.TYPE_NAME.eq(p.TYPE_NAME))
                    .and(GRAPHQL_FIELD.FIELD_NAME.eq(p.FIELD_NAME)))
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(p.GRAPH_NAME)
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.NAMED_TYPE))
                    .and(GRAPHQL_TYPE.KIND.in("OBJECT", "INPUT_OBJECT")))
                .join(e).on(e.SOURCE_NAME.eq(p.SOURCE_NAME)
                    .and(e.CLASS_NAME.eq(p.CLASS_NAME))
                    .and(e.OWNER_KIND.eq("METHOD_RETURN"))
                    .and(e.OWNER_NAME.eq(p.METHOD_NAME))
                    .and(e.OWNER_DESCRIPTOR.eq(p.DESCRIPTOR)))
                .where(p.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    /**
     * One frontier pass: every field of a backed type, read off the class backing it, backs its
     * own named type with what the hop lands on. A coordinate with a producer of its own is
     * skipped, its value coming from that method rather than from the parent's member.
     */
    private static int expand(DSLContext dsl, String graphName) {
        var b = INTENT_TYPE_BACKING_CLASS;
        var backed = INTENT_TYPE_BACKING_CLASS.as("backed");
        var h = INTENT_FIELD_ACCESSOR_HOP;
        var p = INTENT_FIELD_PRODUCER_METHOD;
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
                .and(notExists(selectOne().from(p)
                    .where(p.GRAPH_NAME.eq(h.GRAPH_NAME))
                    .and(p.TYPE_NAME.eq(h.TYPE_NAME))
                    .and(p.FIELD_NAME.eq(h.FIELD_NAME))))
                .and(notExists(selectOne().from(b)
                    .where(b.GRAPH_NAME.eq(graphName))
                    .and(b.TYPE_NAME.eq(GRAPHQL_FIELD.NAMED_TYPE))
                    .and(b.CLASS_NAME.eq(h.TO_CLASS_NAME)))))
            .execute();
    }
}
