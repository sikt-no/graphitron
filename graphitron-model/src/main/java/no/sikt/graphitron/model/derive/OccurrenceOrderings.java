package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ORDER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_METHOD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_INPUT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_INPUT_RULE;
import static org.jooq.impl.DSL.and;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The {@code occurrence-orderings} stage: the orders each table-bound list or connection can be read
 * in and the keys each sorts by, with the {@code @orderBy} input types' shapes they read, each from
 * its rule view over the whole graph; last, the
 * defects of an ordering these cannot state, from the stage's own rule.
 *
 * <p>After {@link OccurrenceLookupKeys}, reading only what {@link OccurrenceBindings} and the stages
 * before it wrote: the table a list's binding reads, the code references the anchor resolved, and the
 * catalog. Each relation is written after the one its rule reads, the argument shapes before the
 * orders their choices are, the orders before their keys, a key before its column or method.
 */
public final class OccurrenceOrderings {

    private OccurrenceOrderings() {}

    /** Writes the stage's relations and its defects, marked with this reading. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        replace(dsl, GRAPHITRON_ORDER_INPUT, GRAPHITRON_ORDER_INPUT_RULE, graph, touchedAt);
        replace(dsl, GRAPHITRON_OCCURRENCE_ORDER, GRAPHITRON_OCCURRENCE_ORDER_RULE, graph, touchedAt);
        replace(dsl, GRAPHITRON_OCCURRENCE_SORT_KEY, GRAPHITRON_OCCURRENCE_SORT_KEY_RULE, graph, touchedAt);
        replace(dsl, GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN, GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN_RULE, graph,
            touchedAt);
        replace(dsl, GRAPHITRON_OCCURRENCE_SORT_KEY_METHOD, GRAPHITRON_OCCURRENCE_SORT_KEY_METHOD_RULE, graph,
            touchedAt);
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.ORDERINGS);
    }

    /**
     * Makes {@code target}'s rows of {@code graph} be {@code rule}'s, whose columns are the target's
     * own but for the instant, matched by name. A row the rule no longer computes, every column
     * compared, goes first, its subtypes with it by cascade, so a key whose kind changed is not held
     * back by a subtype naming the old one; the rest are upserted and marked with this reading.
     */
    @SuppressWarnings("unchecked")
    private static void replace(DSLContext dsl, Table<?> target, Table<?> rule, String graph,
                                LocalDateTime touchedAt) {
        var key = target.getPrimaryKey().getFields();
        Field<LocalDateTime> touched = target.field("TOUCHED_AT", LocalDateTime.class);
        var columns = new ArrayList<Field<?>>();
        var selected = new ArrayList<Field<?>>();
        var same = new ArrayList<Condition>();
        var updates = new LinkedHashMap<Field<?>, Field<?>>();
        for (Field<?> column : target.fields()) {
            if (column.getName().equals(touched.getName())) {
                continue;
            }
            var ruled = (Field<Object>) rule.field(column.getName());
            columns.add(column);
            selected.add(ruled);
            same.add(ruled.isNotDistinctFrom((Field<Object>) column));
            if (!key.contains(column)) {
                updates.put(column, excluded(column));
            }
        }
        var targetGraph = target.field("GRAPH_NAME", String.class);
        dsl.deleteFrom(target)
            .where(targetGraph.eq(graph))
            .andNotExists(selectOne().from(rule).where(and(same)))
            .execute();
        columns.add(touched);
        selected.add(val(touchedAt, touched));
        updates.put(touched, excluded(touched));
        dsl.insertInto(target).columns(columns)
            .select(dsl.select(selected).from(rule).where(rule.field("GRAPH_NAME", String.class).eq(graph)))
            .onConflict(key)
            .doUpdate()
            .set(updates)
            .execute();
        dsl.deleteFrom(target).where(targetGraph.eq(graph), touched.ne(touchedAt)).execute();
    }
}
