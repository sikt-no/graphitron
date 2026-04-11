package no.sikt.graphitron.rewrite.model;

/**
 * An implementing or member type of an interface or union that carries {@code @table}.
 *
 * <p>A {@code ParticipantRef} is only constructed when the type has a {@code @table} directive.
 * When any participant in a union or interface lacks {@code @table} the containing type is
 * classified as {@link GraphitronType.UnclassifiedType} at build time.
 *
 * <p>{@code typeName} is the simple GraphQL type name (e.g. {@code "Film"}).
 *
 * <p>{@code table} is the resolved jOOQ table for this participant type.
 *
 * <p>{@code discriminatorValue} is the value from {@code @discriminator(value:)} on this type,
 * used by the type resolver to map a discriminator column value to a concrete type.
 * {@code null} when {@code @discriminator} is absent.
 */
public record ParticipantRef(String typeName, TableRef table, String discriminatorValue) {}
