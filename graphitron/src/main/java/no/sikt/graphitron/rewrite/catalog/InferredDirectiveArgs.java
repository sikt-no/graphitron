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
 * <p><b>Absent-arm strategy.</b> A non-null {@link Entry#absentArm()} declares that the
 * canonical argument also renders as a synthetic ghost annotation on declarations that
 * omit the directive entirely (not only on present-but-bare directive nodes). Enabling
 * absent rendering requires an {@link AbsentArm} permit, so it cannot be flipped on by
 * accident. Only {@code @table} carries one ({@link AbsentArm.TableName}): {@code @field}
 * would drown the view (one ghost per column-bound field) and {@code @reference} has a
 * different cost/benefit profile.
 */
public final class InferredDirectiveArgs {

    private InferredDirectiveArgs() {}

    /**
     * One inference rule: a directive whose canonical argument is filled in when absent.
     *
     * @param directiveName SDL directive name without the leading {@code @}.
     * @param argName       Canonical argument name (the one inference resolves).
     * @param absentArm     Strategy for rendering a synthetic ghost annotation on
     *                      declarations that carry no directive of this name, or
     *                      {@code null} to disable the absent-directive pass for this
     *                      entry.
     */
    public record Entry(String directiveName, String argName, AbsentArm absentArm) {}

    /**
     * Renderer strategy for the absent-directive arm on an {@link Entry}. Owns both the
     * eligibility check (which classification variants the arm applies to) and the
     * canonical-arg value derived from those variants' projection payload; empty from
     * {@link #resolveAbsentValue} means the classification is outside the arm's
     * eligibility set and the renderer skips the hint silently.
     *
     * <p>Sealed so the absent-arm renderer in the LSP's {@code InlayHints} dispatches
     * over the closed family via virtual call rather than re-matching on
     * {@link Entry#directiveName()}; a new rule is a new permit, with no extra dispatch arm.
     */
    public sealed interface AbsentArm {

        /**
         * Resolves the rendered canonical-arg value from {@code classification}, or
         * empty when this strategy does not apply to that classification variant.
         */
        Optional<String> resolveAbsentValue(TypeClassification classification);

        /**
         * Renders the {@code @table(name:)} canonical value from the table-bound
         * {@link TypeClassification} variants.
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
