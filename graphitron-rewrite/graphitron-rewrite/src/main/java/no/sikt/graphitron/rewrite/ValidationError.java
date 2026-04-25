package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

/**
 * A schema validation error produced by {@link GraphitronSchemaValidator}.
 *
 * <p>{@code location.getSourceName()} carries the source file path, populated automatically
 * when the schema is parsed via {@code RewriteSchemaLoader} (which uses
 * {@code MultiSourceReader.trackData(true)}).
 *
 * <p>{@code kind} categorises the error so downstream tooling and log formatters can
 * distinguish author-correctable mistakes from invalid-schema combinations from
 * generator-deferred features. See {@link RejectionKind}. When wrapping an
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}, propagate the
 * classifier-supplied kind rather than re-deriving it.
 */
public record ValidationError(RejectionKind kind, String message, SourceLocation location) {}
