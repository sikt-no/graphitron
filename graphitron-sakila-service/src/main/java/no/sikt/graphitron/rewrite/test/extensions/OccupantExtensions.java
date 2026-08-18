package no.sikt.graphitron.rewrite.test.extensions;

import org.jooq.Field;
import org.jooq.Table;

/**
 * {@code @externalField} helpers for a nesting type shared across two {@code @table} parents.
 *
 * <p>The parameter is {@code Table<?>} rather than a generated table class, which is the form a
 * shared nesting type requires: the helper is reflected once per anchor against that anchor's own
 * table, so a concretely-typed parameter classifies clean under one parent and is rejected under
 * the other. Widening costs the typed column accessors, so the column is addressed by name off the
 * table handed in.
 *
 * <p>Wired by {@code OccupantLocation.addressId}, whose hosts are {@code Customer} and
 * {@code Store}. Both carry an {@code address_id} column, which is what makes one helper serve
 * both anchors.
 */
public final class OccupantExtensions {

    private OccupantExtensions() {}

    /** The occupant's own FK column to {@code address}, read by name off whichever host table. */
    public static Field<Integer> addressId(Table<?> table) {
        return table.field("address_id", Integer.class);
    }
}
