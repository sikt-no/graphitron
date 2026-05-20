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
 */
public final class InferredDirectiveArgs {

    private InferredDirectiveArgs() {}

    /** One inference rule: a directive whose canonical argument is filled in when absent. */
    public record Entry(String directiveName, String argName) {}

    public static final List<Entry> ENTRIES = List.of(
        new Entry("table", "name"),
        new Entry("field", "name"),
        new Entry("reference", "path")
    );

    /** Returns the inference entry for {@code directiveName}, if any. */
    public static Optional<Entry> findByDirective(String directiveName) {
        for (Entry e : ENTRIES) {
            if (e.directiveName.equals(directiveName)) return Optional.of(e);
        }
        return Optional.empty();
    }
}
