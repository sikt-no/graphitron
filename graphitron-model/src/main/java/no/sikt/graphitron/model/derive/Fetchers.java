package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FETCHER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_fetcher} and its producer side from {@code graphitron_producer_rule},
 * marking what this reading states and sweeping what it no longer does.
 *
 * <p>The last of the graphitron anchor's stages: the rule reads the field targets, the resolved
 * bindings and the type backings the stages before it write.
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

        // A field that stopped being a producer, then one that stopped being fetched at all; the
        // second takes its subtype row with it through the cascade.
        dsl.deleteFrom(producer)
            .where(producer.GRAPH_NAME.eq(graph))
            .and(producer.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(fetcher)
            .where(fetcher.GRAPH_NAME.eq(graph))
            .and(fetcher.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
