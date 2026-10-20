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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FETCHER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_ROUTINE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_SERVICE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_fetcher}, its producer side and what each kind of producer runs, from the
 * rule views beside them, marking what this reading states and sweeping what it no longer does.
 *
 * <p>The last of the graphitron anchor's stages: the rules read the field targets, the resolved
 * bindings and the type backings the stages before it write, and each subtype rule reads the
 * producers this stage has just written.
 */
public final class Fetchers {

    private Fetchers() {}

    private static final String PRODUCER = "PRODUCER";

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var fetcher = GRAPHITRON_FETCHER;
        var producer = GRAPHITRON_PRODUCER;
        var rule = GRAPHITRON_PRODUCER_RULE;

        dsl.insertInto(fetcher)
            .columns(fetcher.GRAPH_NAME, fetcher.TYPE_NAME, fetcher.FIELD_NAME, fetcher.SIDE,
                fetcher.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME,
                    inline(PRODUCER, fetcher.SIDE), val(touchedAt, fetcher.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(fetcher.SIDE, excluded(fetcher.SIDE))
            .set(fetcher.TOUCHED_AT, excluded(fetcher.TOUCHED_AT))
            .execute();

        // A producer whose kind changes keeps its key, and the subtype row naming the old kind would
        // hold the update back; it is no longer true, so it goes before the producer is rewritten.
        for (var subtype : List.of(GRAPHITRON_PRODUCER_WRITE, GRAPHITRON_PRODUCER_ROUTINE,
                GRAPHITRON_PRODUCER_SERVICE)) {
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
            .onDuplicateKeyUpdate()
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

        // A field that stopped being a producer, then one that stopped being fetched at all; each
        // takes what hangs off it through the cascade.
        dsl.deleteFrom(producer)
            .where(producer.GRAPH_NAME.eq(graph))
            .and(producer.TOUCHED_AT.ne(touchedAt))
            .execute();
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
