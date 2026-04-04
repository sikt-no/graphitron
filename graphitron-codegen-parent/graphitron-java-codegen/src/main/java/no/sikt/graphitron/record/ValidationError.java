package no.sikt.graphitron.record;

import graphql.language.SourceLocation;

/**
 * A schema validation error produced by {@link GraphitronSchemaValidator}.
 *
 * <p>{@code location.getSourceName()} carries the source file path, populated automatically
 * when the schema is parsed via {@code SchemaReadingHelper} (which uses
 * {@code MultiSourceReader.trackData(true)}).
 */
public record ValidationError(String message, SourceLocation location) {}
