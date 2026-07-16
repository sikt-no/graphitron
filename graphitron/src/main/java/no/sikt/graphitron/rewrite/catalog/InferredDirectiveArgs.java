package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Canonical-argument-per-directive map for the three current inference targets.
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
 * <p><b>Absent-arm strategy.</b> {@link Entry#absentArm()} carries the renderer
 * strategy for the absent-directive arm: a non-null {@link AbsentArm} declares that
 * the canonical argument should also render as a synthetic ghost annotation on
 * declarations that omit the directive entirely (not only on present-but-bare directive
 * nodes), and the strategy itself owns the eligibility set and the projection accessor.
 * Adding a new entry with absent rendering therefore requires implementing (or reusing)
 * an {@link AbsentArm} permit; flipping it on by accident no-ops at compile time, not at
 * runtime. Today only {@code @table} carries an arm ({@link AbsentArm.TableName});
 * {@code @field} would drown the view (one ghost per column-bound field) and
 * {@code @reference} has a different cost/benefit profile.
 */
public final class InferredDirectiveArgs {

    private InferredDirectiveArgs() {}

    /**
     * One inference rule: a directive whose canonical argument is filled in when absent.
     *
     * @param directiveName SDL directive name without the leading {@code @}.
     * @param argName       Canonical argument name (the one inference resolves).
     * @param absentArm     Non-null when the LSP inlay arm should also render a synthetic
     *                      ghost annotation on declarations that carry no directive of
     *                      this name. The strategy owns the eligibility set (which
     *                      classification variants produce a value) and the resolution
     *                      from classification to rendered argument value. {@code null}
     *                      disables the absent-directive pass for this entry.
     */
    public record Entry(String directiveName, String argName, AbsentArm absentArm) {}

    /**
     * Renderer strategy for the absent-directive arm on an {@link Entry}. Owns both the
     * eligibility check (which classification variants this arm applies to) and the
     * canonical-arg value derived from those variants' projection payload. Returning
     * empty from {@link #resolveAbsentValue} means "this classification is not in this
     * arm's eligibility set"; the renderer skips the hint silently.
     *
     * <p>Sealed so that the absent-arm renderer in {@code InlayHints} dispatches over
     * the closed family of strategies via virtual call rather than re-matching on
     * {@link Entry#directiveName()}. A future absent-arm rule (e.g. {@code @reference}
     * on FK fields, explicitly out of scope) lands as a new
     * permit here and the renderer picks it up without additional dispatch arms.
     */
    public sealed interface AbsentArm {

        /**
         * Resolves the rendered canonical-arg value from {@code classification}, or
         * empty when this strategy does not apply to that classification variant.
         */
        Optional<String> resolveAbsentValue(TypeClassification classification);

        /**
         * Renders the {@code @table(name:)} canonical value from the table-bound
         * {@link TypeClassification} variants ({@code Table}, {@code Node},
         * {@code TableInterface}, {@code TableInput}). The eligibility set is encoded
         * in the switch arms below; non-eligible classifications return empty.
         */
        record TableName() implements AbsentArm {
            @Override
            public Optional<String> resolveAbsentValue(TypeClassification c) {
                return Optional.ofNullable(switch (c) {
                    case TypeClassification.Table t -> t.tableName();
                    case TypeClassification.Node n -> n.tableName();
                    case TypeClassification.TableInterface ti -> ti.tableName();
                    case TypeClassification.TableInput ti -> ti.tableName();
                    default -> null;
                });
            }
        }
    }

    public static final List<Entry> ENTRIES = List.of(
        new Entry("table", "name", new AbsentArm.TableName()),
        new Entry("field", "name", null),
        new Entry("reference", "path", null)
    );

    /** Returns the inference entry for {@code directiveName}, if any. */
    public static Optional<Entry> findByDirective(String directiveName) {
        for (Entry e : ENTRIES) {
            if (e.directiveName.equals(directiveName)) return Optional.of(e);
        }
        return Optional.empty();
    }
}
