package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.tables.records.GraphitronFieldChainLinkResolutionRuleRecord;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_KEYED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_KEYLESS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_OPEN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_RULE;
import static org.jooq.impl.DSL.excluded;

/**
 * The capture-cadence writer of the three chain-link resolution relations: every reading of every
 * written chain link that either walk reaches, and which of them does.
 *
 * <p>Three relations and not one, because the readings have three key shapes.
 * {@code graphitron_field_chain_link_resolution_keyed} holds the readings a foreign key identifies,
 * {@code graphitron_field_chain_link_resolution_keyless} the table pairs none identifies, and
 * {@code graphitron_field_chain_link_resolution_open} the readings that state no departure of their
 * own. The view over the three carries the canonical name every reader spells, and what each column
 * means is documented there.
 *
 * <p>The rule is {@code graphitron_field_chain_link_resolution_rule}, and unlike the hop relations'
 * arms it cannot be split three ways: one chain mixes arms, a routine heading it and a key link
 * after, so each walk crosses all three shapes. This stage therefore evaluates the rule once and
 * files each row under its shape, which is the whole of what it adds to the rule. The {@code EXCEPT}
 * between the view and the rule stays runnable for as long as both exist, and
 * {@code StageAnswerAgreementTest} runs it.
 *
 * <p>Stored because the walk is the dear part of both its readers, {@link FieldTableLinks} and
 * {@link EntryDefects}, and as a view each of them re-walked every chain in the graph.
 *
 * <p>Marked and swept, on {@link FieldTableLinks}' terms: every row this capture wrote carries its
 * instant, and a row still carrying an earlier one is a reading the corpus no longer has.
 */
public final class FieldChainLinkResolutions {

    private FieldChainLinkResolutions() {}

    /** Re-derives the graph's chain-link resolution; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var r = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_RULE;
        var rows = dsl.selectFrom(r).where(r.GRAPH_NAME.eq(graphName)).fetch();
        keyed(dsl, rows.stream().filter(row -> row.getConstraintName() != null).toList(), touchedAt);
        keyless(dsl, rows.stream()
            .filter(row -> row.getConstraintName() == null && row.getFromSourceName() != null)
            .toList(), touchedAt);
        open(dsl, rows.stream()
            .filter(row -> row.getConstraintName() == null && row.getFromSourceName() == null)
            .toList(), touchedAt);

        var keyed = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_KEYED;
        dsl.deleteFrom(keyed)
            .where(keyed.GRAPH_NAME.eq(graphName)).and(keyed.TOUCHED_AT.ne(touchedAt)).execute();
        var keyless = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_KEYLESS;
        dsl.deleteFrom(keyless)
            .where(keyless.GRAPH_NAME.eq(graphName)).and(keyless.TOUCHED_AT.ne(touchedAt)).execute();
        var open = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_OPEN;
        dsl.deleteFrom(open)
            .where(open.GRAPH_NAME.eq(graphName)).and(open.TOUCHED_AT.ne(touchedAt)).execute();
    }

    private static void keyed(DSLContext dsl, List<GraphitronFieldChainLinkResolutionRuleRecord> rows,
                              LocalDateTime touchedAt) {
        if (rows.isEmpty()) {
            return;
        }
        var t = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_KEYED;
        var batch = dsl.batch(dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE,
                t.REACHED_BY_TAIL, t.REACHED_BY_HEAD, t.TOUCHED_AT)
            .values(markers(23))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.REACHED_BY_TAIL, excluded(t.REACHED_BY_TAIL))
            .set(t.REACHED_BY_HEAD, excluded(t.REACHED_BY_HEAD))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
        for (var row : rows) {
            batch = batch.bind(row.getGraphName(), row.getTypeName(), row.getFieldName(),
                row.getTargetSourceName(), row.getTargetSchema(), row.getTargetTable(),
                row.getPosition(), row.getVia(), row.getKeyMatchedBy(),
                row.getConstraintSourceName(), row.getConstraintSchema(), row.getConstraintTable(),
                row.getConstraintName(), row.getFkOnFrom(),
                row.getFromSourceName(), row.getFromSchema(), row.getFromTable(),
                row.getToSourceName(), row.getToSchema(), row.getToTable(),
                row.getReachedByTail(), row.getReachedByHead(), touchedAt);
        }
        batch.execute();
    }

    private static void keyless(DSLContext dsl, List<GraphitronFieldChainLinkResolutionRuleRecord> rows,
                                LocalDateTime touchedAt) {
        if (rows.isEmpty()) {
            return;
        }
        var t = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_KEYLESS;
        var batch = dsl.batch(dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION, t.VIA,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE,
                t.REACHED_BY_TAIL, t.REACHED_BY_HEAD, t.TOUCHED_AT)
            .values(markers(17))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.REACHED_BY_TAIL, excluded(t.REACHED_BY_TAIL))
            .set(t.REACHED_BY_HEAD, excluded(t.REACHED_BY_HEAD))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
        for (var row : rows) {
            batch = batch.bind(row.getGraphName(), row.getTypeName(), row.getFieldName(),
                row.getTargetSourceName(), row.getTargetSchema(), row.getTargetTable(),
                row.getPosition(), row.getVia(),
                row.getFromSourceName(), row.getFromSchema(), row.getFromTable(),
                row.getToSourceName(), row.getToSchema(), row.getToTable(),
                row.getReachedByTail(), row.getReachedByHead(), touchedAt);
        }
        batch.execute();
    }

    private static void open(DSLContext dsl, List<GraphitronFieldChainLinkResolutionRuleRecord> rows,
                             LocalDateTime touchedAt) {
        if (rows.isEmpty()) {
            return;
        }
        var t = GRAPHITRON_FIELD_CHAIN_LINK_RESOLUTION_OPEN;
        var batch = dsl.batch(dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION, t.VIA,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE,
                t.REACHED_BY_TAIL, t.REACHED_BY_HEAD, t.TOUCHED_AT)
            .values(markers(14))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.REACHED_BY_TAIL, excluded(t.REACHED_BY_TAIL))
            .set(t.REACHED_BY_HEAD, excluded(t.REACHED_BY_HEAD))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
        for (var row : rows) {
            batch = batch.bind(row.getGraphName(), row.getTypeName(), row.getFieldName(),
                row.getTargetSourceName(), row.getTargetSchema(), row.getTargetTable(),
                row.getPosition(), row.getVia(),
                row.getToSourceName(), row.getToSchema(), row.getToTable(),
                row.getReachedByTail(), row.getReachedByHead(), touchedAt);
        }
        batch.execute();
    }

    private static List<Object> markers(int count) {
        return Collections.nCopies(count, null);
    }
}
