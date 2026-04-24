package no.sikt.graphitron.rewrite.model;

/**
 * An implementing or member type of an interface or union.
 *
 * <p>Two variants:
 * <ul>
 *   <li>{@link TableBound} — the participant has a resolved jOOQ table and an optional
 *       discriminator value. Generator code may emit SQL for this participant.</li>
 *   <li>{@link Unbound} — the participant is not table-backed (e.g. {@code @error} types,
 *       structural interfaces, value types). Generator code must skip SQL generation for
 *       unbound participants.</li>
 * </ul>
 */
public sealed interface ParticipantRef permits ParticipantRef.TableBound, ParticipantRef.Unbound {

    /** The simple GraphQL type name (e.g. {@code "Film"}). */
    String typeName();

    /**
     * A table-backed participant.
     *
     * <p>{@code table} is always non-null. {@code discriminatorValue} is the value from
     * {@code @discriminator(value:)} on this type, or {@code null} when the directive is absent.
     * Only populated for {@link GraphitronType.TableInterfaceType} participants; always {@code null}
     * for plain {@link GraphitronType.InterfaceType} (multi-table) participants.
     */
    record TableBound(String typeName, TableRef table, String discriminatorValue)
            implements ParticipantRef {}

    /**
     * A non-table-backed participant.
     *
     * <p>Used for {@code @error} types, structural interfaces, and other types that carry no
     * jOOQ table. Generator switches must handle this variant and skip SQL-emitting paths.
     */
    record Unbound(String typeName) implements ParticipantRef {}
}
