package no.sikt.graphitron.rewrite.test.extensions;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Region;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.time.LocalDate;

/**
 * {@code @externalField} helpers over {@code region}, for a nesting type reached through a field
 * named like the {@code valid_period} column: a daterange, whose Java type,
 * {@code LocalDateRange}, has no accessor for the nesting type's fields, so the nesting type is read
 * off the parent's row and its fields are computed from the range here.
 */
public final class RegionExtensions {

    private RegionExtensions() {}

    /** The first day of the period, the range's inclusive lower bound. */
    public static Field<LocalDate> validFrom(Region table) {
        return DSL.field("lower({0})", SQLDataType.LOCALDATE, table.VALID_PERIOD);
    }

    /** The last day of the period, the day before the range's exclusive upper bound. */
    public static Field<LocalDate> validTo(Region table) {
        return DSL.field("(upper({0}) - 1)", SQLDataType.LOCALDATE, table.VALID_PERIOD);
    }
}
