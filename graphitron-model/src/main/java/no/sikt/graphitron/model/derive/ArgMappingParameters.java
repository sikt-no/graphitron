package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;

/**
 * The capture-cadence writer of {@code graphitron_argmapping_parameter}: the Java parameter each
 * written {@code argMapping} entry binds.
 *
 * <p>The join is the whole of the rule. An entry's string sits under a code reference
 * ({@code graphitron_argmapping_site}), the reference resolves to the one method it names
 * ({@code graphitron_code_reference}), and that method's parameters carry the names an entry
 * targets ({@code code_method_parameter.parameter_name}, NULL where the class was compiled without
 * {@code -parameters}, which never equals a name). An entry that resolves nowhere has no row, which
 * is what a defect arm anti-joins on.
 *
 * <p>Reconciles the graph's partition: clears it, then re-derives.
 */
public final class ArgMappingParameters {

    private ArgMappingParameters() {}

    /** Reconciles the graph's argMapping-parameter bindings: clears the partition, then re-derives. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_ARGMAPPING_PARAMETER;
        var pair = GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
        var site = GRAPHITRON_ARGMAPPING_SITE;
        var reference = GRAPHITRON_CODE_REFERENCE;
        var parameter = CODE_METHOD_PARAMETER;

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE, target.SOURCE_COLUMN,
                target.POSITION, target.METHOD_SOURCE_NAME, target.METHOD_CLASS_NAME,
                target.METHOD_NAME, target.METHOD_DESCRIPTOR, target.PARAMETER_POSITION)
            .select(dsl
                .select(pair.GRAPH_NAME, pair.SOURCE_NAME, pair.SOURCE_LINE, pair.SOURCE_COLUMN,
                    pair.POSITION, parameter.SOURCE_NAME, parameter.CLASS_NAME,
                    parameter.METHOD_NAME, parameter.DESCRIPTOR, parameter.POSITION)
                .from(pair)
                .join(site).on(site.GRAPH_NAME.eq(pair.GRAPH_NAME),
                    site.SOURCE_NAME.eq(pair.SOURCE_NAME),
                    site.SOURCE_LINE.eq(pair.SOURCE_LINE),
                    site.SOURCE_COLUMN.eq(pair.SOURCE_COLUMN))
                .join(reference).on(reference.GRAPH_NAME.eq(site.GRAPH_NAME),
                    reference.SOURCE_NAME.eq(site.SOURCE_NAME),
                    reference.SOURCE_LINE.eq(site.REFERENCE_LINE),
                    reference.SOURCE_COLUMN.eq(site.REFERENCE_COLUMN))
                .join(parameter).on(parameter.SOURCE_NAME.eq(reference.METHOD_SOURCE_NAME),
                    parameter.CLASS_NAME.eq(reference.METHOD_CLASS_NAME),
                    parameter.METHOD_NAME.eq(reference.METHOD_NAME),
                    parameter.DESCRIPTOR.eq(reference.METHOD_DESCRIPTOR),
                    parameter.PARAMETER_NAME.eq(pair.PARAM_NAME))
                .where(pair.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
