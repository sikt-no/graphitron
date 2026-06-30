package no.sikt.graphitron.rewrite.lint;

import graphql.language.SourceLocation;

import java.util.List;

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
}
