package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.FanBase;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * {@code @externalField} extension methods over {@code fan_base}, the single-table discriminated
 * interface fixture.
 */
public final class FanExtensions {

    private FanExtensions() {}

    /**
     * Returns {@code <fan_kind>: <label>}, a value distinct from the {@code label} column it is
     * built from.
     *
     * <p>Wired by {@code FanLabelled.label} and both its participants, a field that shares its
     * name with the {@code label} column on purpose: the computed term is read back under its
     * result-key alias, so it returns this value while a sibling {@code @field(name: "label")}
     * still reads the column.
     */
    public static Field<String> kindLabel(FanBase table) {
        return DSL.concat(table.FAN_KIND, DSL.inline(": "), table.LABEL);
    }
}
