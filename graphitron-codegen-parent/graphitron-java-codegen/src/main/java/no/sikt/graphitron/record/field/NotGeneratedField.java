package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * {@code @notGenerated} — Graphitron classifies the field and includes it in the schema model
 * but produces no data fetcher. The developer registers wiring externally.
 * Valid in any source context.
 */
public record NotGeneratedField(GraphQLFieldDefinition definition) implements GraphitronField {}
