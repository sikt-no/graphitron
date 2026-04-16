package no.sikt.graphitron.rewrite.model;

/**
 * An implementing or member type of an interface or union.
 *
 * <p>{@code typeName} is the simple GraphQL type name (e.g. {@code "Film"}).
 *
 * <p>{@code table} is the resolved jOOQ table for this participant type, or {@code null} for
 * non-table-bound types such as {@code @error} types, structural interfaces, or value types.
 * Generator code must check {@link #isTableBound()} before emitting SQL for a participant.
 *
 * <p>{@code discriminatorValue} is the value from {@code @discriminator(value:)} on this type,
 * used by the type resolver to map a discriminator column value to a concrete type.
 * {@code null} when {@code @discriminator} is absent or the type is not table-bound.
 */
public record ParticipantRef(String typeName, TableRef table, String discriminatorValue) {

    /** Returns {@code true} when this participant has an associated jOOQ table. */
    public boolean isTableBound() {
        return table != null;
    }
}
