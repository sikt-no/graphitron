package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.Target;
import no.sikt.graphitron.rewrite.model.TargetShape;

import java.util.List;

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

    /**
     * The summary verdict as a precedence fold over the coordinate's operation member rows:
     * the corpus's {@code operation:} vocabulary, derived from the member relation instead of
     * the retiring summary column. Reads the leaf projection
     * ({@link OperationMembers#membersOf}, pinned equal to the minted production), so ridden
     * fields outside the minted relation's flat domain fold the same way. Transient window
     * artifact: the corpus voice re-grains to member-set assertions and this fold retires with
     * it.
     *
     * <p>Two arms are deliberately not member-keyed. {@code Paginate} reads connection-ness
     * off the target axis (the paginate member is gated on a carried window payload, and a
     * connection-shaped coordinate without one, the batched polymorphic connection, still
     * summarised {@code Paginate}). The empty-set arm is the summary column's fiction for the
     * no-operation coordinates: {@code Nest} for the structural nesting embed (the unique
     * empty-set shape with a bare table target), {@code Fetch} for the record / passthrough
     * reads, distinguished by target shape because the member relation deliberately says
     * nothing about them.
     */
    public static Class<? extends Operation> summaryArmOf(OutputField field) {
        List<OperationMember> members = OperationMembers.membersOf(field);
        for (OperationMember m : members) {
            if (m instanceof OperationMember.Write w) {
                return switch (w) {
                    case OperationMember.Write.Insert ignored -> Operation.Insert.class;
                    case OperationMember.Write.Upsert ignored -> Operation.Upsert.class;
                    case OperationMember.Write.Update ignored -> Operation.Update.class;
                    case OperationMember.Write.Delete ignored -> Operation.Delete.class;
                    case OperationMember.Write.RoutineWrite ignored -> Operation.RoutineWrite.class;
                    case OperationMember.Write.UpdateMatching ignored -> Operation.UpdateMatching.class;
                    case OperationMember.Write.DeleteMatching ignored -> Operation.DeleteMatching.class;
                };
            }
        }
        if (hasKind(members, OperationMember.Kind.NODE_RESOLVE)) {
            return Operation.NodeResolve.class;
        }
        if (hasKind(members, OperationMember.Kind.SERVICE_CALL)) {
            return Operation.ServiceCall.class;
        }
        if (hasKind(members, OperationMember.Kind.LOOKUP)) {
            return Operation.Lookup.class;
        }
        if (hasKind(members, OperationMember.Kind.PIVOT)) {
            return Operation.Pivot.class;
        }
        if (field.target().shape() instanceof TargetShape.Connection) {
            return Operation.Paginate.class;
        }
        if (hasKind(members, OperationMember.Kind.SELECT)) {
            return Operation.Fetch.class;
        }
        return field.target().shape() instanceof TargetShape.Table
            ? Operation.Nest.class
            : Operation.Fetch.class;
    }

    private static boolean hasKind(List<OperationMember> members, OperationMember.Kind kind) {
        return members.stream().anyMatch(m -> m.kind() == kind);
    }
}
