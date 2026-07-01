package no.sikt.graphitron.rewrite.lint;

import graphql.language.Directive;
import graphql.language.SourceLocation;
import graphql.schema.GraphQLAppliedDirective;

import java.util.List;
import java.util.Optional;

/**
 * An optional, user-accepted suggested fix carried on a
 * {@link no.sikt.graphitron.rewrite.BuildWarning.LintFinding}. A fix is a <em>suggestion</em>, never
 * a build-time mutation: the build never rewrites the consumer's SDL. The LSP turns a fix-bearing
 * finding into a {@code QuickFix} {@code CodeAction} the developer chooses to apply.
 *
 * <p>The fix is a typed, ordered list of {@link Edit}s, each a half-open source range
 * ({@code start} inclusive, {@code end} exclusive, both as graphql-java
 * {@link SourceLocation}s with 1-based line/column) plus the {@code replacement} text. An additive
 * edit (insert, change nothing existing) uses a zero-width range where {@code start.equals(end)};
 * the replacement is the inserted text. The rule owns its fix; the LSP only projects it, with no
 * LSP-side recompute of how to fix a given rule.
 *
 * <p>A fix is supplied only where the edit is safe within the SDL document: additive inserts, and
 * local token replacements that no other SDL construct references. A rule whose only correct fix
 * would ripple to references it cannot see (a type rename) supplies no fix in v1; its finding
 * carries {@code Optional.empty()}.
 */
public record LintFix(String description, List<Edit> edits) {

    public LintFix {
        edits = List.copyOf(edits);
    }

    /**
     * A single text edit: replace the half-open range {@code [start, end)} with {@code replacement}.
     * For an insertion, {@code start} and {@code end} are equal (a zero-width range) and
     * {@code replacement} is the text to insert at that point.
     */
    public record Edit(SourceLocation start, SourceLocation end, String replacement) {}

    /** Convenience for a single-edit fix. */
    public static LintFix of(String description, SourceLocation start, SourceLocation end, String replacement) {
        return new LintFix(description, List.of(new Edit(start, end, replacement)));
    }

    /**
     * A single-token replacement: replace the {@code tokenLength} characters starting at
     * {@code start} with {@code replacement}. A token never spans a line, so the end is
     * {@code start.column + tokenLength} on the same line. Used for the local-rename fixes
     * (a field name, a directive name).
     */
    public static LintFix replaceToken(
        String description, SourceLocation start, int tokenLength, String replacement
    ) {
        return of(description, start, endOnSameLine(start, tokenLength), replacement);
    }

    /**
     * A safe token deletion: remove the {@code tokenLength} characters starting at {@code start}
     * (a zero-width replacement). Used for the classifier safe-deletion advisories (a redundant
     * {@code @record} / {@code @splitQuery}). The token itself is removed exactly; any surrounding
     * whitespace is left intact (a lone extra space is valid SDL), so no source text is needed to
     * compute the range.
     */
    public static LintFix deleteToken(String description, SourceLocation start, int tokenLength) {
        return of(description, start, endOnSameLine(start, tokenLength), "");
    }

    /**
     * An additive insertion at {@code at}: a zero-width edit ({@code start.equals(end)}) whose
     * replacement is the inserted text. Nothing existing is changed. Used for the additive fixes
     * (insert a {@code reason:} placeholder, insert a description placeholder).
     */
    public static LintFix insertAt(String description, SourceLocation at, String text) {
        return of(description, at, at, text);
    }

    private static SourceLocation endOnSameLine(SourceLocation start, int tokenLength) {
        return new SourceLocation(start.getLine(), start.getColumn() + tokenLength);
    }

    /**
     * A safe deletion fix for a redundant applied directive whose value graphitron ignores anyway
     * (the classifier advisories {@code redundant-record-directive} and
     * {@code splitquery-redundant-on-record-parent}). The fix is offered only for the bare form (no
     * arguments): graphql-java records a node's start location but no end, so the span of
     * {@code @record(record: {...})} cannot be derived, whereas a bare {@code @record} /
     * {@code @splitQuery} is exactly {@code '@' + name}. Returns empty when the directive is absent,
     * carries arguments, or has no retained source location (a programmatically built schema).
     */
    public static Optional<LintFix> deleteBareAppliedDirective(
        GraphQLAppliedDirective applied, String description
    ) {
        if (applied == null) return Optional.empty();
        Directive definition = applied.getDefinition();
        if (definition == null || !definition.getArguments().isEmpty()) return Optional.empty();
        SourceLocation at = definition.getSourceLocation();
        if (at == null) return Optional.empty();
        return Optional.of(deleteToken(description, at, 1 + applied.getName().length()));
    }
}
