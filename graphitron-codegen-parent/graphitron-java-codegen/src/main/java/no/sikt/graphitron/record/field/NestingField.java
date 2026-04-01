package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A field whose target inherits the source table context, producing a level of nesting without
 * a scope boundary. Table-mapped source context only.
 */
public record NestingField(GraphQLFieldDefinition definition) implements ChildField {}
