package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

/**
 * The three-axis classification verdict the corpus asserts: a {@link Source} (arrival endpoint),
 * an {@link Operation} arm (verb), and a {@link Target} (projection endpoint), the dimensional
 * fingerprint the {@code @classified} directive carries, exposed by the field model through
 * {@code GraphitronSchema.sourceOf} / {@link OutputField#operation()} / {@link OutputField#target()}.
 *
 * <p>Each axis is compared at the altitude the {@code @classified} directive can express, the
 * <em>classification coordinate</em> (arm identity), not the payload:
 * <ul>
 *   <li><b>source</b>: full structural equality; {@link Source} carries no heavy payload and is
 *       fully reconstructible from the {@code source:} / {@code sourceShape:} directive arguments.</li>
 *   <li><b>operation</b>: the {@link Operation} arm type token only. The arm payload (a
 *       {@link Operation.Fetch}'s filters, a {@link Operation.ServiceCall}'s call, ...) is not
 *       reconstructible from the directive; payload completeness is the obligation of the
 *       pipeline / execution tiers that compile and run the generated resolvers.</li>
 *   <li><b>target</b>: the {@link Target} wrapper arm token plus the outer {@link TargetShape}
 *       arm token. A {@link TargetShape.Connection}'s inner shape is not asserted at the connection
 *       coordinate; the decomposition's many-ness rides the connection type's own {@code edges} /
 *       {@code nodes} fields, classified as their own coordinates.</li>
 * </ul>
 *
 * <p>The tuple is the primary fingerprint, not the complete emit key: derived facts (re-fetch,
 * new-query) and orthogonal slots (FK path, fetcher / loader mechanism, error channel) live beside
 * these axes, so two leaves differing only in a slot share one tuple.
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
     * assertion. The {@code source} arm is a parent-grain fact the leaf cannot compute alone, so
     * the caller supplies it (via {@code GraphitronSchema.sourceOf}); the {@code operation} /
     * {@code target} arms are leaf-derived.
     */
    public static DimensionTuple of(OutputField field, Source source) {
        return new DimensionTuple(source, field.operation().getClass(), TargetVerdict.of(field.target()));
    }
}
