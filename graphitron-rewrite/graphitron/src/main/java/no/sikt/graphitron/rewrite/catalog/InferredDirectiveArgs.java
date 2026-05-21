package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * R160 — canonical-argument-per-directive map for the three current inference targets.
 * Single source of truth for "which directive arguments does Graphitron's inference layer
 * fill in when the author omits them?".
 *
 * <ul>
 *   <li>{@code @table(name:)} resolved from the SDL type name.</li>
 *   <li>{@code @field(name:)} resolved from the SDL field name.</li>
 *   <li>{@code @reference(path:)} resolved from a unique single-hop FK.</li>
 * </ul>
 *
 * <p>Consumers (today: the LSP's inferred-directive inlay-hint arm) read from this table
 * rather than encoding the directive / argument name pairs as switch literals. A future
 * inference rule adds one entry here and downstream consumers either pick it up
 * automatically or fail to compile when they need a rendering arm to match.
 *
 * <p>String identity (not constant identity) is the contract; the directive-vocabulary
 * source-of-truth in {@code BuildContext} uses the same string literals.
 *
 * <p><b>R217 absent-eligibility flag.</b> {@link Entry#renderWhenAbsent()} marks entries
 * whose canonical argument should also render as a ghost inlay on declarations that omit
 * the directive entirely (not only on present-but-bare directive nodes). The eligibility
 * set, projection lookup, and rendered string live in the LSP renderer arm; this flag
 * is the dispatch signal that gates the second pass.
 */
public final class InferredDirectiveArgs {

    private InferredDirectiveArgs() {}

    /**
     * One inference rule: a directive whose canonical argument is filled in when absent.
     *
     * @param directiveName     SDL directive name without the leading {@code @}.
     * @param argName           Canonical argument name (the one inference resolves).
     * @param renderWhenAbsent  When {@code true}, the LSP inlay arm also renders a
     *                          synthetic ghost annotation on type declarations whose
     *                          classification is in the renderer's eligibility set <em>and</em>
     *                          that carry no directive of this name. Today only
     *                          {@code @table} flips this on; {@code @field} would drown
     *                          the view (one ghost per column-bound field) and
     *                          {@code @reference} has a different cost/benefit profile,
     *                          per R217's judgement calls.
     */
    public record Entry(String directiveName, String argName, boolean renderWhenAbsent) {}

    public static final List<Entry> ENTRIES = List.of(
        new Entry("table", "name", true),
        new Entry("field", "name", false),
        new Entry("reference", "path", false)
    );

    /** Returns the inference entry for {@code directiveName}, if any. */
    public static Optional<Entry> findByDirective(String directiveName) {
        for (Entry e : ENTRIES) {
            if (e.directiveName.equals(directiveName)) return Optional.of(e);
        }
        return Optional.empty();
    }
}
