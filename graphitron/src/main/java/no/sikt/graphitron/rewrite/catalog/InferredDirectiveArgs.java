package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Canonical-argument-per-directive map: single source of truth for which directive
 * arguments Graphitron's inference layer fills in when the author omits them.
 *
 * <ul>
 *   <li>{@code @table(name:)} resolved from the SDL type name.</li>
 *   <li>{@code @field(name:)} resolved from the SDL field name.</li>
 *   <li>{@code @reference(path:)} resolved from a unique single-hop FK.</li>
 * </ul>
 *
 * <p>Consumers (the LSP's inferred-directive inlay-hint arm) read from this table rather
 * than encoding directive / argument name pairs as switch literals; a new inference rule
 * is one added entry, which consumers pick up automatically or fail to compile against.
 *
 * <p>String identity (not constant identity) is the contract; the directive-vocabulary
 * source of truth in {@code BuildContext} uses the same string literals.
 *
 * <p>What this table does <em>not</em> carry is where a resolved value is read from. That
 * differs per directive and is the consumer's own business: the LSP answers all three off the
 * fact store, {@code @table} from the binding relation, {@code @field} from the resolved column
 * claim or the class member slot, {@code @reference} from the foreign-key discovery, and none of
 * those choices is a property of which argument inference fills in.
 */
public final class InferredDirectiveArgs {

    private InferredDirectiveArgs() {}

    /**
     * One inference rule: a directive whose canonical argument is filled in when absent.
     *
     * @param directiveName SDL directive name without the leading {@code @}.
     * @param argName       Canonical argument name (the one inference resolves).
     */
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
