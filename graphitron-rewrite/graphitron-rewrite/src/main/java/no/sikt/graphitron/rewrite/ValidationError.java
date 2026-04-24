package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

/**
 * A schema validation error produced by {@link GraphitronSchemaValidator}.
 *
 * <p>{@code location.getSourceName()} carries the source file path, populated automatically
 * when the schema is parsed via {@code RewriteSchemaLoader} (which uses
 * {@code MultiSourceReader.trackData(true)}).
 */
public record ValidationError(String message, SourceLocation location) {}
