package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * {@code @computed} — the developer provides a jOOQ {@code Field<?>} expression (scalar,
 * {@code row(...)}, or {@code multiset(...)}). Included in the SELECT; Graphitron does not
 * project through it. LiftCondition applies if the return type is table-mapped.
 * Table-mapped source context only.
 */
public record ComputedField(GraphQLFieldDefinition definition) implements ChildField {}
