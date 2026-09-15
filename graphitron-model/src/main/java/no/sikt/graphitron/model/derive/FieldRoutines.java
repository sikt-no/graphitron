package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.partitionBy;

/**
 * The capture-cadence writer of {@code graphitron_field_routine}: which catalog table each
 * {@code @routine} application resolved to, at the chain it stands in.
 *
 * <p>Called as a stage of the graphitron gatherer after {@link FieldEndpoints}, whose relation this
 * one's key points into. Everything else it reads has flushed by then: the applications are the
 * SDL crawler's transcription and carry their own spelling, and the catalog is the crawler's.
 *
 * <p>A routine result is a catalog table of kind {@code FUNCTION}, so an authored routine name
 * resolves the way an authored table name does, and this states that answer once. Two readers were
 * folding the spelling themselves and neither could serve the other: one keeps only the last
 * application of a field because it wants the chain's target, and the other compares the whole
 * spelling where this compares it in parts, so a qualified name is a disagreement waiting to
 * happen.
 *
 * <p>Every application resolves, not only the last. {@code @routine} is repeatable and composes
 * with {@code @reference} into one chain in written order, so a function can stand anywhere in it;
 * a relation holding one application per field could answer only for chains that contain one.
 *
 * <p>The match folds case on both sides, and a spelling with no namespace is admitted against any
 * schema the graph's sources carry, which is what an author writing an unqualified name means. A
 * spelling two schemas both answer resolves to two candidates and draws no row, on
 * {@code graphitron_tabletype}'s terms: the count is over one application rather than over the
 * field, since two applications of one field are two questions and neither makes the other
 * ambiguous.
 */
public final class FieldRoutines {

    private FieldRoutines() {}

    /** Clears and re-derives the graph's resolved routine applications; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        // Cleared first so the call is idempotent, on FieldEndpoints' terms: capture makes it once
        // per graph, and a caller re-deriving in order to read the result makes it as often as it
        // likes.
        dsl.deleteFrom(GRAPHITRON_FIELD_ROUTINE)
            .where(GRAPHITRON_FIELD_ROUTINE.GRAPH_NAME.eq(graphName)).execute();

        var ft = GRAPHITRON_FIELD_TABLE;
        var r = GRAPHITRON_ROUTINE_ENTRY;
        var gs = STORE_GRAPH_SOURCE;
        var st = SQL_TABLE;

        var resolved = dsl.select(ft.GRAPH_NAME, ft.TYPE_NAME, ft.FIELD_NAME,
                ft.TO_SOURCE_NAME, ft.TO_SCHEMA, ft.TO_TABLE, r.ORDINAL,
                st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                // Per application and not per field: a field carrying two @routine applications
                // asks two questions, and one of them being answerable twice says nothing about
                // the other.
                count().over(partitionBy(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME, r.ORDINAL,
                        ft.TO_SOURCE_NAME, ft.TO_SCHEMA, ft.TO_TABLE))
                    .as("candidates"))
            .from(ft)
            .join(r).on(r.GRAPH_NAME.eq(ft.GRAPH_NAME), r.TYPE_NAME.eq(ft.TYPE_NAME),
                r.FIELD_NAME.eq(ft.FIELD_NAME))
            .join(gs).on(gs.GRAPH_NAME.eq(r.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(gs.SOURCE_NAME),
                st.TABLE_TYPE.eq("FUNCTION"),
                st.TABLE_NAME_UPPER.eq(r.ROUTINE_REF_NAME_PART_UPPER))
            .and(r.ROUTINE_REF_NAMESPACE_PART_UPPER.isNull()
                .or(st.TABLE_SCHEMA_UPPER.eq(r.ROUTINE_REF_NAMESPACE_PART_UPPER)))
            .where(ft.GRAPH_NAME.eq(graphName))
            .asTable("resolved");

        dsl.insertInto(GRAPHITRON_FIELD_ROUTINE)
            .columns(GRAPHITRON_FIELD_ROUTINE.GRAPH_NAME, GRAPHITRON_FIELD_ROUTINE.TYPE_NAME,
                GRAPHITRON_FIELD_ROUTINE.FIELD_NAME,
                GRAPHITRON_FIELD_ROUTINE.TO_SOURCE_NAME, GRAPHITRON_FIELD_ROUTINE.TO_SCHEMA,
                GRAPHITRON_FIELD_ROUTINE.TO_TABLE, GRAPHITRON_FIELD_ROUTINE.ORDINAL,
                GRAPHITRON_FIELD_ROUTINE.RESULT_SOURCE_NAME,
                GRAPHITRON_FIELD_ROUTINE.RESULT_SCHEMA, GRAPHITRON_FIELD_ROUTINE.RESULT_TABLE)
            .select(dsl.select(
                    resolved.field(ft.GRAPH_NAME), resolved.field(ft.TYPE_NAME),
                    resolved.field(ft.FIELD_NAME),
                    resolved.field(ft.TO_SOURCE_NAME), resolved.field(ft.TO_SCHEMA),
                    resolved.field(ft.TO_TABLE), resolved.field(r.ORDINAL),
                    resolved.field(st.SOURCE_NAME), resolved.field(st.TABLE_SCHEMA),
                    resolved.field(st.TABLE_NAME))
                .from(resolved)
                .where(field(name("resolved", "candidates"), Integer.class).eq(1)))
            .execute();
    }
}
