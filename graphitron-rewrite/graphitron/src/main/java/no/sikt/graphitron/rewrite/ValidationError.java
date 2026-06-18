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
 * validation failed. The {@link Rejection} hierarchy gives the validator's near-miss checks
 * an on-ramp to typed {@link Rejection.AuthorError.UnknownName} (with candidate lists), typed
 * {@link Rejection.InvalidSchema.DirectiveConflict} (with conflicting-directive lists), and
 * typed {@link Rejection.Deferred} (with planSlug + stubKey) without re-parsing prose.
 * The {@link #kind()} and {@link #message()} accessors project the variant for the byte-stable
 * validator log surface.
 */
public record ValidationError(String coordinate, Rejection rejection, SourceLocation location) {
    public RejectionKind kind() { return RejectionKind.of(rejection); }
    public String message() { return rejection.message(); }

    /**
     * Type-level diagnostic factory: wraps {@code rejection} with the {@code "Type '<name>': "}
     * coordinate prefix and pins {@code coordinate} to the type name. Single home for that prefix
     * convention, shared by {@link GraphitronSchemaValidator}'s {@code validateUnclassifiedType}
     * pass (honest classification-time {@code UnclassifiedType} verdicts) and the immutable validate
     * phase's build-time diagnostics (R317 slice 5: node-typeId / case-fold / federation reductions
     * that register here instead of demoting). Both producers route through this method so the error
     * stream stays byte-identical by construction rather than by a prose convention duplicated across
     * sites.
     */
    public static ValidationError forType(String typeName, Rejection rejection, SourceLocation location) {
        return new ValidationError(typeName, rejection.prefixedWith("Type '" + typeName + "': "), location);
    }

    /**
     * Field-level diagnostic factory: wraps {@code rejection} with the {@code "Field '<qname>': "}
     * coordinate prefix and pins {@code coordinate} to the field's qualified name. Field-axis sibling
     * of {@link #forType}, shared by {@code validateUnclassifiedField} and the dangling-reference
     * backstop (R317 slice 5); see {@link #forType} for the single-home rationale.
     */
    public static ValidationError forField(String qualifiedName, Rejection rejection, SourceLocation location) {
        return new ValidationError(qualifiedName, rejection.prefixedWith("Field '" + qualifiedName + "': "), location);
    }
}
