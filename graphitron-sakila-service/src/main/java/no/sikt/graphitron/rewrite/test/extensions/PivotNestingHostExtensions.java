package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.PivotNestingHost;
import org.jooq.Field;

/**
 * {@code @externalField} helpers over {@code pivot_nesting_host}'s nullable label columns, for the
 * null contract of an object made of {@code @externalField}s: each expression is null exactly when
 * its column is, so a wrapper of them must resolve to {@code null} on the same hosts as the
 * column-backed {@code HostLabels}.
 *
 * <p>Wired by {@code HostLabelsComputed}, {@code HostLabelsComputedRow} and
 * {@code HostLabelsMixed}.
 */
public final class PivotNestingHostExtensions {

    private PivotNestingHostExtensions() {}

    /** The host's {@code nn} label. */
    public static Field<String> nn(PivotNestingHost table) {
        return table.NN;
    }

    /** The host's {@code nb} label. */
    public static Field<String> nb(PivotNestingHost table) {
        return table.NB;
    }
}
