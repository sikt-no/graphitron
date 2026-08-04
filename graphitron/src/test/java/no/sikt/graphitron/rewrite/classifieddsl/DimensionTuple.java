package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

import java.util.Comparator;
import java.util.List;

/**
 * The classification verdict the corpus asserts: a {@link Source} (arrival endpoint), the
 * coordinate's {@link OperationMember} rows (the operation axis, asserted as an arm-token
 * multiset), and a {@link Target} (projection endpoint), the dimensional fingerprint the
 * {@code @classified} directive carries, exposed by the field model through
 * {@code GraphitronSchema.sourceOf} / {@code GraphitronSchema.operationMembersOf} /
 * {@link OutputField#target()}.
 *
 * <p>Each axis is compared at the altitude the {@code @classified} directive can express, the
 * <em>classification coordinate</em> (arm identity), not the payload:
 * <ul>
 *   <li><b>source</b>: full structural equality; {@link Source} carries no heavy payload and is
 *       fully reconstructible from the {@code source:} / {@code sourceShape:} directive arguments.</li>
 *   <li><b>operations</b>: the {@link OperationMember} arm type tokens, one list entry per member
 *       row, sorted by simple name (a multiset: the condition kind's per-table row count is
 *       voiced, the table keys are not). The row payloads (a condition member's filters, a
 *       serviceCall member's call, ...) are not reconstructible from the directive; payload
 *       completeness is the obligation of the pipeline / execution tiers that compile and run
 *       the generated resolvers, and per-payload agreement is
 *       {@code OperationMemberMintPinTest}'s.</li>
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
public record DimensionTuple(Source source,
                             List<Class<? extends OperationMember>> operations,
                             TargetVerdict target) {

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
     * assertion. The {@code source} arm is a parent-grain fact the leaf cannot compute alone, and
     * the member rows are the relation's, not the leaf's, so the caller supplies both through the
     * schema seams every consumer reads ({@code GraphitronSchema.sourceOf} /
     * {@code GraphitronSchema.operationMembersOf}); the {@code target} arm is leaf-derived.
     */
    public static DimensionTuple of(OutputField field, Source source, List<OperationMember> members) {
        return new DimensionTuple(source, memberArmsOf(members), TargetVerdict.of(field.target()));
    }

    /**
     * The operation axis at the corpus's altitude: one arm type token per member row, sorted by
     * simple name. A multiset, not a set: the condition kind admits one row per table, and the
     * row count is asserted content (a dropped participant row must fail the corpus) even though
     * the table keys are payload grain the directive never names.
     */
    public static List<Class<? extends OperationMember>> memberArmsOf(List<OperationMember> members) {
        return members.stream()
            .<Class<? extends OperationMember>>map(m -> m.getClass())
            .sorted(Comparator.comparing(Class::getSimpleName))
            .toList();
    }

}
