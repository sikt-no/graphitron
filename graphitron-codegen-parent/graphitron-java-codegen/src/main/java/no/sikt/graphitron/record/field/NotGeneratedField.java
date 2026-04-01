package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A field annotated with {@code @notGenerated}. Classified but no data fetcher is generated.
 */
public record NotGeneratedField(String name, SourceLocation location) implements GraphitronField {}
