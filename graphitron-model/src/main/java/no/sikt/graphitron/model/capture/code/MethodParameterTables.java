package no.sikt.graphitron.model.capture.code;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER_TABLE;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER_TABLE_RULE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code code_method_parameter_table}: which catalog tables each parameter of a method
 * can be handed, the one rule every call taking a table reads.
 *
 * <p>The code gatherer's anchor stage after {@link GraphClasses}, declaring {@code jooq} upstream,
 * whose tables it pairs with the methods this reading wrote. Its rows are keyed by the method's
 * classpath entry and the table's catalog, never by the graph: the fit is a function of those two
 * sources alone, so every graph reaching a pair computes the same rows. The graph only scopes the
 * work: it marks the pairs of its own entries and catalogs, then sweeps, among those pairs, the rows
 * its reading did not mark.
 */
public final class MethodParameterTables {

    private MethodParameterTables() {}

    /** Marks the fits among the graph's entries and catalogs, then sweeps those it no longer finds. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var target = CODE_METHOD_PARAMETER_TABLE;
        var rule = CODE_METHOD_PARAMETER_TABLE_RULE;
        var method = STORE_GRAPH_SOURCE.as("method_source");
        var table = STORE_GRAPH_SOURCE.as("table_source");
        dsl.insertInto(target)
            .columns(target.SOURCE_NAME, target.CLASS_NAME, target.METHOD_NAME, target.DESCRIPTOR,
                target.POSITION, target.TABLE_SOURCE_NAME, target.TABLE_SCHEMA, target.TABLE_NAME,
                target.FIT, target.TOUCHED_AT)
            .select(dsl
                .select(rule.SOURCE_NAME, rule.CLASS_NAME, rule.METHOD_NAME, rule.DESCRIPTOR,
                    rule.POSITION, rule.TABLE_SOURCE_NAME, rule.TABLE_SCHEMA, rule.TABLE_NAME,
                    rule.FIT, val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(target.FIT, excluded(target.FIT))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(target)
            .where(target.TOUCHED_AT.ne(touchedAt))
            .andExists(selectOne().from(method)
                .where(method.GRAPH_NAME.eq(graph), method.SOURCE_NAME.eq(target.SOURCE_NAME)))
            .andExists(selectOne().from(table)
                .where(table.GRAPH_NAME.eq(graph), table.SOURCE_NAME.eq(target.TABLE_SOURCE_NAME)))
            .execute();
    }
}
