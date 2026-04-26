package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

/**
 * A schema validation error produced by {@link GraphitronSchemaValidator}.
 *
 * <p>{@code location.getSourceName()} carries the source file path, populated automatically
 * when the schema is parsed via {@code RewriteSchemaLoader} (which uses
 * {@code MultiSourceReader.trackData(true)}).
 *
 * <p>{@code coordinate} is the schema element the error attaches to: a type name like
 * {@code "User"} for type-level errors, a {@code "Type.field"} qualified name for field-level
 * errors, or {@code null} for schema-wide errors that cannot be pinned to a single element.
 * Watch-mode formatters group by file, then by {@code coordinate}, so two errors on the same
 * field collapse under one heading; the delta tracker keys on
 * {@code (file, coordinate, kind, message)} so unrelated line shifts do not flag every error
 * as new.
 *
 * <p>{@code kind} categorises the error so downstream tooling and log formatters can
 * distinguish author-correctable mistakes from invalid-schema combinations from
 * generator-deferred features. See {@link RejectionKind}. When wrapping an
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}, propagate the
 * classifier-supplied kind rather than re-deriving it.
 */
public record ValidationError(RejectionKind kind, String coordinate, String message, SourceLocation location) {
}
