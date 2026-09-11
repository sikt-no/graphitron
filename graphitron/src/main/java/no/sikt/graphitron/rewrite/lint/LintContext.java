package no.sikt.graphitron.rewrite.lint;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.lint.DeprecationRecognizer;
import no.sikt.graphitron.model.lint.LintFix;

/**
 * What a rule may ask beyond the node it was handed: where to report, and the two questions about
 * the corpus that a single node cannot answer.
 *
 * <p>Both of those are reads of the fact store now rather than of a parsed registry. A rule asking
 * whether something is deprecated, or what type a directive declares an argument to be, is asking
 * about the corpus, and the corpus is a set of rows.
 */
public interface LintContext {

    /** Reports a finding at the node's own location. */
    void report(String message);

    /** Reports a finding carrying a quick fix. */
    void report(String message, LintFix fix);

    /** Reports a finding at an explicit location, for a sub-node of the target. */
    void reportAt(SourceLocation location, String message);

    /** Whether the corpus marks something deprecated, by either of graphitron's two markers. */
    DeprecationRecognizer deprecation();

    /**
     * The type a declared directive gives one of its arguments, unwrapped of list and non-null, or
     * null where the corpus declares no such argument. What a rule needs it for is the coordinate
     * an object field written inside that argument's value belongs to.
     */
    String namedTypeOfDirectiveArgument(String directive, String argument);
}
