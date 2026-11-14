package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_argmapping_pair_defect}: the entry-grain detail
 * of the defects {@code graphitron_entry_defect} states once per mapping string.
 *
 * <p>Each stage raising argMapping entry codes has a pair rule of its own beside its entry-defect
 * rule view, which raises the string's defect from the same statement in a CTE; the stage runs this
 * after {@link EntryDefects#derive}, so its rows hang off the defects just written, each under the
 * defect of its own code and stage.
 *
 * <p>Reconciles the stage's part of the graph's partition: clears it, then re-derives.
 */
public final class ArgMappingPairDefects {

    private ArgMappingPairDefects() {}

    /** argmapping-pairs' pair rule: the session, context and scope sigils. */
    public static final Table<?> PAIRS = GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE;

    /** call-context-keys' pair rule: a {@code $context} pair writing a key written elsewhere too. */
    public static final Table<?> CONTEXT_KEYS = GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE;

    /** argmapping-landings' pair rule: a path that lands nowhere. */
    public static final Table<?> LANDINGS = GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE;

    /** The outbox stage's pair rule: a source found that cannot fill a payload key. */
    public static final Table<?> OUTBOX = GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE;

    /**
     * Reconciles the graph's entry-grain defects {@code rule} computes, under the string defects
     * {@code stage} raised, the stage that runs this: clears that stage's part of the partition, then
     * re-derives it.
     */
    public static void derive(DSLContext dsl, String graphName, Table<?> rule, String stage) {
        var target = GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
        var d = GRAPHITRON_ENTRY_DEFECT;
        Field<String> graph = rule.field(target.GRAPH_NAME.getName(), String.class);
        Field<String> file = rule.field(target.SOURCE_NAME.getName(), String.class);
        Field<Integer> line = rule.field(target.SOURCE_LINE.getName(), Integer.class);
        Field<Integer> column = rule.field(target.SOURCE_COLUMN.getName(), Integer.class);
        Field<Integer> position = rule.field(target.POSITION.getName(), Integer.class);
        Field<String> code = rule.field(target.CODE.getName(), String.class);
        Field<String> detail = rule.field(target.DETAIL.getName(), String.class);

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName), target.STAGE.eq(stage)).execute();

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE,
                target.SOURCE_COLUMN, target.POSITION, target.CODE, target.STAGE, target.DETAIL)
            .select(dsl
                .select(graph, file, line, column, position, code, val(stage, target.STAGE), detail)
                .from(rule)
                .where(graph.eq(graphName))
                .andExists(selectOne().from(d)
                    .where(d.GRAPH_NAME.eq(graph), d.SOURCE_NAME.eq(file), d.SOURCE_LINE.eq(line),
                        d.SOURCE_COLUMN.eq(column), d.CODE.eq(code), d.STAGE.eq(stage))))
            .execute();
    }
}
