package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.Rejection;

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
 * <p>{@code rejection} is the typed sealed-variant explanation of why classification or
 * validation failed. R58 Phase I lifted this from a flat {@code (RejectionKind kind, String
 * message)} pair onto the {@link Rejection} hierarchy so the validator's near-miss checks have
 * an on-ramp to typed {@link Rejection.AuthorError.UnknownName} (with candidate lists), typed
 * {@link Rejection.InvalidSchema.DirectiveConflict} (with conflicting-directive lists), and
 * typed {@link Rejection.Deferred} (with planSlug + stubKey) without re-parsing prose.
 * The {@link #kind()} and {@link #message()} accessors project the variant for the byte-stable
 * validator log surface.
 */
public record ValidationError(String coordinate, Rejection rejection, SourceLocation location) {
    public RejectionKind kind() { return RejectionKind.of(rejection); }
    public String message() { return rejection.message(); }
}
