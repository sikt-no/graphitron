package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Language;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.PivotNestingHost;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * {@code @externalField} helpers over {@code pivot_nesting_host}'s nullable label columns, for the
 * null contract of an object made of {@code @externalField}s: each expression is null exactly when
 * its column is, so a wrapper of them must resolve to {@code null} on the same hosts as the
 * column-backed {@code HostLabels}.
 *
 * <p>Wired by {@code HostLabelsComputed}, {@code HostLabelsComputedRow} and
 * {@code HostLabelsMixed}; the language helpers by {@code HostLanguageNames}, the language a host
 * reaches through {@code pivot_nesting_host.language_id}.
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

    /** The language's name when it is English, null otherwise. */
    public static Field<String> englishName(Language table) {
        return DSL.when(table.NAME.eq("English"), table.NAME);
    }

    /** The language's name when it is Italian, null otherwise. */
    public static Field<String> italianName(Language table) {
        return DSL.when(table.NAME.eq("Italian"), table.NAME);
    }
}
