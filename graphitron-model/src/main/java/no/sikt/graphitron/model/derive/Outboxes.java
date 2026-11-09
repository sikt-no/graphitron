package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_MESSAGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_MESSAGE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ROUTINE_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ROUTINE_TARGET_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_TARGET_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.when;
import static org.jooq.impl.DSL.val;

/**
 * The outbox stage of the graphitron anchor phase: the outbox targets the configuration resolves to
 * in the catalog, and each {@code @outbox} as the message its field's call writes, then the defects
 * those break.
 *
 * <p>Then each message's keys, the parameters of its call, each read off its landing's binding. Each
 * relation is written from its {@code _rule} view, marked with the reading's instant and
 * swept of the graph's rows a reading did not mark. The defects are this stage's own rule,
 * {@link EntryDefects#OUTBOX}, written last, so they read the rows this stage has just written. Runs
 * after the occurrences and their producers, which a message's placement is judged against.
 */
public final class Outboxes {

    private Outboxes() {}

    /** Writes {@code graph}'s targets and messages, then their defects. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        copy(dsl, graph, touchedAt, GRAPHITRON_OUTBOX_TARGET, GRAPHITRON_OUTBOX_TARGET_RULE,
            GRAPHITRON_OUTBOX_TARGET.TOUCHED_AT);
        copy(dsl, graph, touchedAt, GRAPHITRON_OUTBOX_ROUTINE_TARGET,
            GRAPHITRON_OUTBOX_ROUTINE_TARGET_RULE, GRAPHITRON_OUTBOX_ROUTINE_TARGET.TOUCHED_AT);
        messages(dsl, graph, touchedAt);
        keys(dsl, graph, touchedAt);
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.OUTBOX);
    }

    private static void messages(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_OUTBOX_MESSAGE;
        var r = GRAPHITRON_OUTBOX_MESSAGE_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.EVENT, t.SOURCE_NAME,
                t.SOURCE_LINE, t.SOURCE_COLUMN, t.OCCURRENCE_GRAPH_REF, t.OCCURRENCE_COORDINATE_REF,
                t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.DIRECTIVE_NAME, r.ORDINAL, r.EVENT,
                    r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    when(r.OCCURRENCE_COORDINATE.isNotNull(), r.GRAPH_NAME)
                        .otherwise(inline((String) null)),
                    r.OCCURRENCE_COORDINATE, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.ORDINAL)
            .doUpdate()
            .set(t.EVENT, excluded(t.EVENT))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.OCCURRENCE_GRAPH_REF, excluded(t.OCCURRENCE_GRAPH_REF))
            .set(t.OCCURRENCE_COORDINATE_REF, excluded(t.OCCURRENCE_COORDINATE_REF))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).and(t.TOUCHED_AT.ne(touchedAt)).execute();
    }

    /** Each message's keys, from the key rule, marked and swept. */
    private static void keys(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_OUTBOX_KEY;
        var r = GRAPHITRON_OUTBOX_KEY_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.ORDINAL, t.KEY_NAME, t.ORIGIN, t.POSITION, t.FILL,
                t.READS, t.SOURCE_PATH, t.LANDING_COORDINATE, t.COLUMN_NAME, t.NODE_TYPE_NAME,
                t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.ORDINAL, r.KEY_NAME, r.ORIGIN, r.POSITION,
                    r.FILL, r.READS, r.SOURCE_PATH, r.LANDING_COORDINATE, r.COLUMN_NAME,
                    r.NODE_TYPE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.ORDINAL, t.KEY_NAME)
            .doUpdate()
            .set(t.ORIGIN, excluded(t.ORIGIN))
            .set(t.POSITION, excluded(t.POSITION))
            .set(t.FILL, excluded(t.FILL))
            .set(t.READS, excluded(t.READS))
            .set(t.SOURCE_PATH, excluded(t.SOURCE_PATH))
            .set(t.LANDING_COORDINATE, excluded(t.LANDING_COORDINATE))
            .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
            .set(t.NODE_TYPE_NAME, excluded(t.NODE_TYPE_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).and(t.TOUCHED_AT.ne(touchedAt)).execute();
    }

    /**
     * Upserts {@code rule}'s rows for {@code graph} into {@code target}, which has the rule's columns
     * by name and a mark, keyed by the graph alone, then sweeps what the reading did not mark.
     */
    private static void copy(DSLContext dsl, String graph, LocalDateTime touchedAt,
                             Table<? extends Record> target, Table<? extends Record> rule,
                             TableField<? extends Record, LocalDateTime> mark) {
        List<Field<?>> columns = new ArrayList<>();
        List<Field<?>> values = new ArrayList<>();
        for (Field<?> column : target.fields()) {
            if (column.equals(mark)) {
                continue;
            }
            columns.add(column);
            values.add(rule.field(column.getName()));
        }
        columns.add(mark);
        values.add(val(touchedAt, mark));
        Field<String> graphColumn = target.field("GRAPH_NAME", String.class);
        var insert = dsl.insertInto(target).columns(columns)
            .select(dsl.select(values).from(rule)
                .where(rule.field("GRAPH_NAME", String.class).eq(graph)))
            .onConflict(graphColumn)
            .doUpdate();
        var update = insert.set(mark, excluded(mark));
        for (Field<?> column : columns) {
            if (!column.equals(graphColumn) && !column.equals(mark)) {
                update = setExcluded(update, column);
            }
        }
        update.execute();
        dsl.deleteFrom(target).where(graphColumn.eq(graph)).and(mark.ne(touchedAt)).execute();
    }

    private static <T> org.jooq.InsertOnDuplicateSetMoreStep<? extends Record> setExcluded(
            org.jooq.InsertOnDuplicateSetMoreStep<? extends Record> step, Field<T> column) {
        return step.set(column, excluded(column));
    }
}
