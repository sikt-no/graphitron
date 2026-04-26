package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

/**
 * A non-fatal advisory produced by {@link GraphitronSchemaBuilder} during classification.
 *
 * <p>Shape-parallel to {@link ValidationError}: a {@code message} plus the SDL
 * {@link SourceLocation} of the problematic construct. Warnings are surfaced by
 * {@code ValidateMojo} / {@code GenerateMojo} via {@code getLog().warn(...)} and never fail
 * the build — they exist to flag schema shapes that are accepted for compatibility but are
 * discouraged (e.g. directives shadowed by another directive on the same declaration).
 *
 * <p>{@code location.getSourceName()} carries the source file path when the schema was parsed
 * via {@code RewriteSchemaLoader}.
 */
public record BuildWarning(String message, SourceLocation location) {}
