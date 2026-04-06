package no.sikt.graphitron.rewrite.type;

/**
 * Captures an implementing or member type of an interface or union, together with
 * the outcome of resolving its {@code @table} directive against the jOOQ catalog.
 *
 * <p>{@link BoundParticipant} means the type carries {@code @table} (it is a table-bound type).
 * {@link UnboundParticipant} means the type does not carry {@code @table}. The
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for every
 * {@code UnboundParticipant}.
 */
public sealed interface ParticipantRef permits ParticipantRef.BoundParticipant, ParticipantRef.UnboundParticipant {

    String typeName();

    /**
     * The implementing/member type has {@code @table}; {@code table} is the resolution outcome
     * ({@link TableRef.ResolvedTable} or {@link TableRef.UnresolvedTable}).
     *
     * <p>{@code discriminatorValue} is the value from {@code @discriminator(value:)} on this
     * type, used by the type resolver to map a discriminator column value to a concrete type.
     * {@code null} when {@code @discriminator} is absent.
     */
    record BoundParticipant(String typeName, TableRef table, String discriminatorValue) implements ParticipantRef {}

    /** The implementing/member type does not have {@code @table}. */
    record UnboundParticipant(String typeName) implements ParticipantRef {}
}
