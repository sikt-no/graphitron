package no.sikt.graphitron.record.field;

/**
 * A {@link ReferencePathElement} where a key name was specified in the schema but could not be
 * found in the jOOQ catalog.
 *
 * <p>{@code keyName} is the SQL name of the foreign key constant as written in the schema
 * (e.g. {@code "FILM_ACTOR_FK"}). The {@link no.sikt.graphitron.record.GraphitronSchemaValidator}
 * reports this as an error.
 */
public record UnresolvedKeyStep(String keyName) implements ReferencePathElement {}
