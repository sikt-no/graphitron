package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

/**
 * The three-axis classification verdict the R281 corpus asserts: a {@link Source} (the arrival
 * endpoint), an {@link Operation} arm (the verb), and a {@link Target} (the projection endpoint). This is
 * the dimensional fingerprint the {@code @classified} directive carries and the field model exposes
 * directly through {@code GraphitronSchema.sourceOf} (R463: the arrival fold) / {@link OutputField#operation()} /
 * {@link OutputField#target()}.
 *
 * <p>The axes are not all flat, so the tuple compares each at the altitude the {@code @classified}
 * directive can actually express, the <em>classification coordinate</em> (arm identity), not the
 * payload:
 * <ul>
 *   <li><b>source</b> is compared by full structural equality, because {@link Source} carries no heavy
 *       payload (only a {@code SourceShape} enum on its nested arms) and is fully reconstructible from
 *       the {@code source:} / {@code sourceShape:} directive arguments.</li>
 *   <li><b>operation</b> is compared by its {@link Operation} arm type token. The arm carries a payload
 *       (a {@link Operation.Fetch}'s filters, a {@link Operation.ServiceCall}'s call, ...) the directive
 *       provably cannot reconstruct, so the corpus asserts which arm the classifier landed on, not its
 *       contents. The payload-completeness obligation lives with the pipeline / execution tiers that
 *       compile and run the generated resolvers, not here.</li>
 *   <li><b>target</b> is compared by its {@link Target} wrapper arm token and its outer
 *       {@link TargetShape} arm token. The {@link TargetShape.Connection} container's inner shape is not
 *       asserted at the connection coordinate (the decomposition's many-ness rides the connection
 *       type's own {@code edges} / {@code nodes} fields, classified as their own coordinates).</li>
 * </ul>
 *
 * <p>The tuple is the primary fingerprint, not the complete emit key: the derived facts (re-fetch,
 * new-query) and the orthogonal slots (the FK path, fetcher / loader mechanism, error channel) live
 * beside these three axes, so two leaves differing only in a slot share one tuple.
 */
public record DimensionTuple(Source source, Class<? extends Operation> operation, TargetVerdict target) {

    /**
     * The shallow target coordinate: the {@link Target} wrapper arm ({@link Target.Single} /
     * {@link Target.List}) and the outer {@link TargetShape} arm, ignoring a
     * {@link TargetShape.Connection}'s inner shape.
     */
    public record TargetVerdict(Class<? extends Target> wrapper, Class<? extends TargetShape> shape) {
        public static TargetVerdict of(Target target) {
            return new TargetVerdict(target.getClass(), target.shape().getClass());
        }
    }

    /**
     * The verdict the field model produces for {@code field}, the {@code actual} side of a corpus
 * assertion. The {@code source} arm is the ancestor-product fold, which is a parent-grain
     * fact the leaf cannot compute alone, so the caller supplies it (via {@code GraphitronSchema.sourceOf});
     * the {@code operation} / {@code target} arms are leaf-derived.
     */
    public static DimensionTuple of(OutputField field, Source source) {
        return new DimensionTuple(source, field.operation().getClass(), TargetVerdict.of(field.target()));
    }
}
