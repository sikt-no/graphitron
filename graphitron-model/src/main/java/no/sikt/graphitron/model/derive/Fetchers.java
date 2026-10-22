package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FETCHER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_OUTCOME_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_ROUTINE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_SERVICE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_fetcher}: its producer side and what each kind of producer runs, with the
 * errors fields a root producer's outcome catches into, the results they make through
 * {@link Results}, and the reader side that reads them, from the rule views
 * beside each, marking what this reading states and sweeping what it no longer does.
 *
 * <p>The last of the graphitron anchor's stages: the rules read the field targets, the resolved
 * bindings and the type backings the stages before it write, and each subtype rule reads the
 * producers this stage has just written.
 */
public final class Fetchers {

    private Fetchers() {}

    private static final String PRODUCER = "PRODUCER";
    private static final String READER = "READER";

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var fetcher = GRAPHITRON_FETCHER;
        var producer = GRAPHITRON_PRODUCER;
        var rule = GRAPHITRON_PRODUCER_RULE;
        var reader = GRAPHITRON_READER;
        var readerRule = GRAPHITRON_READER_RULE;

        // The errors fields, a fact of the field whoever returns its type, ahead of the outcomes.
        refresh(dsl, GRAPHITRON_ERRORS_FIELD, GRAPHITRON_ERRORS_FIELD_RULE,
            GRAPHITRON_ERRORS_FIELD.GRAPH_NAME, GRAPHITRON_ERRORS_FIELD.TOUCHED_AT, graph, touchedAt);

        // A field that was a reader and is a producer now: its reader row names the side the
        // fetcher is about to leave, so it goes first.
        dsl.deleteFrom(reader)
            .where(reader.GRAPH_NAME.eq(graph))
            .andExists(dsl.selectOne().from(rule)
                .where(rule.GRAPH_NAME.eq(reader.GRAPH_NAME))
                .and(rule.TYPE_NAME.eq(reader.TYPE_NAME))
                .and(rule.FIELD_NAME.eq(reader.FIELD_NAME)))
            .execute();

        dsl.insertInto(fetcher)
            .columns(fetcher.GRAPH_NAME, fetcher.TYPE_NAME, fetcher.FIELD_NAME, fetcher.SIDE,
                fetcher.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME,
                    inline(PRODUCER, fetcher.SIDE), val(touchedAt, fetcher.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            // The key named: left to find any key, the upsert matches the primary key or the unique
            // one, and an OR finds no index, so every graph's fetchers were scanned per capture.
            .onConflict(fetcher.GRAPH_NAME, fetcher.TYPE_NAME, fetcher.FIELD_NAME)
            .doUpdate()
            .set(fetcher.SIDE, excluded(fetcher.SIDE))
            .set(fetcher.TOUCHED_AT, excluded(fetcher.TOUCHED_AT))
            .execute();

        // A producer whose kind changes keeps its key, and the subtype row naming the old kind would
        // hold the update back; it is no longer true, so it goes before the producer is rewritten.
        for (var subtype : List.of(GRAPHITRON_PRODUCER_WRITE, GRAPHITRON_PRODUCER_ROUTINE,
                GRAPHITRON_PRODUCER_SERVICE, GRAPHITRON_PRODUCER_OUTCOME)) {
            dsl.deleteFrom(subtype)
                .where(subtype.field("GRAPH_NAME", String.class).eq(graph))
                .andNotExists(dsl.selectOne().from(rule)
                    .where(rule.GRAPH_NAME.eq(subtype.field("GRAPH_NAME", String.class)))
                    .and(rule.TYPE_NAME.eq(subtype.field("TYPE_NAME", String.class)))
                    .and(rule.FIELD_NAME.eq(subtype.field("FIELD_NAME", String.class)))
                    .and(rule.KIND.eq(subtype.field("KIND", String.class))))
                .execute();
        }

        dsl.insertInto(producer)
            .columns(producer.GRAPH_NAME, producer.TYPE_NAME, producer.FIELD_NAME, producer.SIDE,
                producer.KIND, producer.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME,
                    inline(PRODUCER, producer.SIDE), rule.KIND,
                    val(touchedAt, producer.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            .onConflict(producer.GRAPH_NAME, producer.TYPE_NAME, producer.FIELD_NAME)
            .doUpdate()
            .set(producer.KIND, excluded(producer.KIND))
            .set(producer.TOUCHED_AT, excluded(producer.TOUCHED_AT))
            .execute();

        // What each kind runs, off the producers just written.
        refresh(dsl, GRAPHITRON_PRODUCER_WRITE, GRAPHITRON_PRODUCER_WRITE_RULE,
            GRAPHITRON_PRODUCER_WRITE.GRAPH_NAME, GRAPHITRON_PRODUCER_WRITE.TOUCHED_AT, graph, touchedAt);
        refresh(dsl, GRAPHITRON_PRODUCER_ROUTINE, GRAPHITRON_PRODUCER_ROUTINE_RULE,
            GRAPHITRON_PRODUCER_ROUTINE.GRAPH_NAME, GRAPHITRON_PRODUCER_ROUTINE.TOUCHED_AT, graph, touchedAt);
        refresh(dsl, GRAPHITRON_PRODUCER_SERVICE, GRAPHITRON_PRODUCER_SERVICE_RULE,
            GRAPHITRON_PRODUCER_SERVICE.GRAPH_NAME, GRAPHITRON_PRODUCER_SERVICE.TOUCHED_AT, graph, touchedAt);
        refresh(dsl, GRAPHITRON_PRODUCER_OUTCOME, GRAPHITRON_PRODUCER_OUTCOME_RULE,
            GRAPHITRON_PRODUCER_OUTCOME.GRAPH_NAME, GRAPHITRON_PRODUCER_OUTCOME.TOUCHED_AT, graph, touchedAt);

        // A field that stopped being a producer, before the readers are written, since it may be one.
        dsl.deleteFrom(producer)
            .where(producer.GRAPH_NAME.eq(graph))
            .and(producer.TOUCHED_AT.ne(touchedAt))
            .execute();

        // The results the producers make, flowing down through the fields that read them.
        Results.derive(dsl, graph, touchedAt);

        // Every other field of a type holding a result reads it.
        dsl.insertInto(fetcher)
            .columns(fetcher.GRAPH_NAME, fetcher.TYPE_NAME, fetcher.FIELD_NAME, fetcher.SIDE,
                fetcher.TOUCHED_AT)
            .select(dsl
                .select(readerRule.GRAPH_NAME, readerRule.TYPE_NAME, readerRule.FIELD_NAME,
                    inline(READER, fetcher.SIDE), val(touchedAt, fetcher.TOUCHED_AT))
                .from(readerRule)
                .where(readerRule.GRAPH_NAME.eq(graph)))
            .onConflict(fetcher.GRAPH_NAME, fetcher.TYPE_NAME, fetcher.FIELD_NAME)
            .doUpdate()
            .set(fetcher.SIDE, excluded(fetcher.SIDE))
            .set(fetcher.TOUCHED_AT, excluded(fetcher.TOUCHED_AT))
            .execute();
        dsl.insertInto(reader)
            .columns(reader.GRAPH_NAME, reader.TYPE_NAME, reader.FIELD_NAME, reader.SIDE,
                reader.READS, reader.TOUCHED_AT)
            .select(dsl
                .select(readerRule.GRAPH_NAME, readerRule.TYPE_NAME, readerRule.FIELD_NAME,
                    inline(READER, reader.SIDE), readerRule.READS,
                    val(touchedAt, reader.TOUCHED_AT))
                .from(readerRule)
                .where(readerRule.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(reader.READS, excluded(reader.READS))
            .set(reader.TOUCHED_AT, excluded(reader.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(reader)
            .where(reader.GRAPH_NAME.eq(graph))
            .and(reader.TOUCHED_AT.ne(touchedAt))
            .execute();

        // A field that stopped being fetched at all.
        dsl.deleteFrom(fetcher)
            .where(fetcher.GRAPH_NAME.eq(graph))
            .and(fetcher.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * Upserts {@code target} from {@code rule}, whose columns are the target's own but for the
     * instant, then sweeps the rows this reading did not touch. The rule's columns are matched to
     * the target's by name, so a column added to both is carried without an edit here.
     */
    private static <R extends Record> void refresh(DSLContext dsl, Table<R> target, Table<?> rule,
                                                   TableField<R, String> graphName,
                                                   TableField<R, LocalDateTime> touched,
                                                   String graph, LocalDateTime touchedAt) {
        var key = target.getPrimaryKey().getFields();
        List<Field<?>> columns = new ArrayList<>();
        List<Field<?>> selected = new ArrayList<>();
        Map<Field<?>, Field<?>> updates = new HashMap<>();
        for (Field<?> column : target.fields()) {
            if (column.equals(touched)) {
                continue;
            }
            columns.add(column);
            selected.add(rule.field(column.getName()));
            if (!key.contains(column)) {
                updates.put(column, excluded(column));
            }
        }
        columns.add(touched);
        selected.add(val(touchedAt, touched));
        updates.put(touched, excluded(touched));
        dsl.insertInto(target)
            .columns(columns)
            .select(dsl.select(selected).from(rule).where(rule.field(graphName.getName(), String.class).eq(graph)))
            .onDuplicateKeyUpdate()
            .set(updates)
            .execute();
        dsl.deleteFrom(target)
            .where(graphName.eq(graph))
            .and(touched.ne(touchedAt))
            .execute();
    }
}
