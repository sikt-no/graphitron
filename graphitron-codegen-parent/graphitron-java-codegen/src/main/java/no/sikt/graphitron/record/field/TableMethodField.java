package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * {@code @tableMethod} — the developer provides a filtered {@code Table<?>}. Graphitron joins it
 * using the same logic as {@link TableField}. Preferred over {@link ServiceField} when the logic
 * can be expressed as a filtered table.
 */
public record TableMethodField(GraphQLFieldDefinition definition) implements ChildField {}
