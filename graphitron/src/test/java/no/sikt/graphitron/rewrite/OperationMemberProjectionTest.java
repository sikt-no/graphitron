package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedHarness;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OperationMember.Kind;
import no.sikt.graphitron.rewrite.model.OperationMembers;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.TargetShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The population pin for the member view against the summary column: over every classified
 * output coordinate the corpus fixtures produce, the member set the schema view serves (the
 * minted trigger-fact production since the keystone) must equal a member set derived
 * <em>independently</em> here, from the summary {@link Operation} arm plus the coordinate's
 * other facts (target shape, source shape, the leaf's capability slots), never from either
 * production's own dispatch. Together with the membership-agreement pin
 * ({@link OperationMemberMintPinTest}, minted equals leaf-projected) this closes a three-way
 * agreement: summary derivation, leaf projection and trigger mint cannot drift pairwise.
 *
 * <p>Alongside the per-coordinate agreement, this pins the declaration's totality (every sealed
 * {@link OutputField} leaf carries exactly one {@link OperationMembers#DECLARED_SHAPES} entry)
 * and the scan's non-vacuity (the corpus must actually exercise the member vocabulary's
 * populated kinds, so the agreement cannot pass by matching empty sets).
 */
@PipelineTier
class OperationMemberProjectionTest {

    @Test
    void declaredShapesCoverExactlyTheSealedLeaves() {
        Set<Class<?>> leaves = GeneratorCoverageTest.sealedLeaves(OutputField.class);
        assertThat(OperationMembers.DECLARED_SHAPES.keySet())
            .as("every sealed OutputField leaf declares its member shape, and no entry outlives its leaf")
            .containsExactlyInAnyOrderElementsOf(leaves.stream()
                .map(c -> {
                    @SuppressWarnings("unchecked")
                    Class<? extends OutputField> cast = (Class<? extends OutputField>) c;
                    return cast;
                })
                .collect(Collectors.toSet()));
    }

    @Test
    void projectedMembersAgreeWithTheSummaryDerivationOverTheCorpus() {
        var observedKinds = new EnumMap<Kind, Integer>(Kind.class);
        int coordinates = 0;

        for (var example : ClassifiedCorpus.examples()) {
            GraphitronSchema schema = ClassifiedHarness.classify(example.sdl()).schema();
            for (var entry : schema.fields().entrySet()) {
                if (!(entry.getValue() instanceof OutputField leaf)) {
                    continue;
                }
                coordinates++;
                List<OperationMember> produced = schema.operationMembersOf(entry.getKey());
                Set<Kind> producedKinds = produced.stream()
                    .map(OperationMember::kind)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class)));
                producedKinds.forEach(k -> observedKinds.merge(k, 1, Integer::sum));

                assertThat(producedKinds)
                    .as("member kinds at %s.%s (%s in %s); produced %s",
                        leaf.parentTypeName(), leaf.name(), leaf.getClass().getSimpleName(),
                        example.id(), produced)
                    .isEqualTo(expectedKinds(leaf));

                assertPayloadsMirrorTheSummary(leaf, produced, example.id());
            }
        }

        assertThat(coordinates)
            .as("the corpus scan must not be vacuous")
            .isGreaterThan(50);
        assertThat(observedKinds.keySet())
            .as("the corpus must exercise the populated member vocabulary; observed histogram %s",
                observedKinds)
            .contains(Kind.SELECT, Kind.JOIN, Kind.PAGINATE, Kind.LOOKUP, Kind.SERVICE_CALL,
                Kind.NODE_RESOLVE, Kind.PIVOT, Kind.WRITE, Kind.REENTRY);
    }

    /**
     * The independent derivation: the member kinds the coordinate's summary arm and facts
     * imply. Reads the {@link Operation} arm's own payload slots plus the leaf's capability
     * interfaces; deliberately never calls {@link OperationMembers}.
     */
    private static Set<Kind> expectedKinds(OutputField leaf) {
        var kinds = EnumSet.noneOf(Kind.class);
        Operation op = leaf.operation();
        switch (op) {
            case Operation.Fetch f -> {
                if (catalogProjection(leaf)) {
                    kinds.add(Kind.SELECT);
                    if (!f.filters().isEmpty()) {
                        kinds.add(Kind.CONDITION);
                    }
                    if (!(f.orderBy() instanceof OrderBySpec.None)) {
                        kinds.add(Kind.ORDER_BY);
                    }
                }
            }
            case Operation.Paginate p -> {
                kinds.add(Kind.SELECT);
                if (p.pagination() != null) {
                    kinds.add(Kind.PAGINATE);
                }
                if (!p.filters().isEmpty()) {
                    kinds.add(Kind.CONDITION);
                }
                if (!(p.orderBy() instanceof OrderBySpec.None)) {
                    kinds.add(Kind.ORDER_BY);
                }
            }
            case Operation.Lookup _ -> {
                kinds.add(Kind.SELECT);
                kinds.add(Kind.LOOKUP);
                addSqlSurface(kinds, leaf);
            }
            case Operation.ServiceCall _ -> {
                kinds.add(Kind.SERVICE_CALL);
                addSqlSurface(kinds, leaf);
            }
            case Operation.Nest _ -> { /* a regroup: no query-composing member */ }
            case Operation.Pivot _ -> {
                kinds.add(Kind.PIVOT);
                kinds.add(Kind.JOIN);
            }
            case Operation.NodeResolve _ -> kinds.add(Kind.NODE_RESOLVE);
            case Operation.Insert _, Operation.Upsert _, Operation.Update _, Operation.Delete _,
                 Operation.RoutineWrite _ -> kinds.add(Kind.WRITE);
            case Operation.Count _, Operation.Facet _, Operation.EntityResolve _,
                 Operation.UpdateMatching _, Operation.DeleteMatching _ ->
                throw new AssertionError("no classified leaf mints " + op.getClass().getSimpleName());
        }

        // The reference fact mints join: read off the leaves that carry a resolved path.
        boolean hasJoinPath = switch (leaf) {
            case ChildField.TableTargetField ttf -> !ttf.joinPath().isEmpty();
            case ChildField.ColumnBackedReferenceField cbr -> !cbr.joinPath().isEmpty();
            case ChildField.ParticipantColumnReferenceField _ -> true;
            case ChildField.ComputedField cf -> !cf.joinPath().isEmpty();
            default -> false;
        };
        if (hasJoinPath) {
            kinds.add(Kind.JOIN);
        }

        // A polymorphic root's per-participant filter surface, which the one-arm summary drops.
        List<no.sikt.graphitron.rewrite.model.ParticipantFilters> participantFilters = switch (leaf) {
            case QueryField.QueryInterfaceField f -> f.participantFilters();
            case QueryField.QueryUnionField f -> f.participantFilters();
            default -> List.of();
        };
        if (participantFilters.stream().anyMatch(pf -> !pf.filters().isEmpty())) {
            kinds.add(Kind.CONDITION);
        }

        // The site-level reentry, restated from the facts (the pre-member formula).
        boolean bareTableTarget = leaf.target().shape() instanceof TargetShape.Table;
        boolean receivedRecord = leaf instanceof ChildField cf && cf.sourceShape() == SourceShape.Record;
        boolean producedRecord = switch (op) {
            case Operation.ServiceCall _, Operation.Insert _, Operation.Update _,
                 Operation.Upsert _, Operation.Delete _ -> true;
            default -> false;
        };
        boolean requiresReFetch = bareTableTarget && (receivedRecord || producedRecord);
        if (requiresReFetch && (!(op instanceof Operation.ServiceCall) || leaf instanceof ChildField)) {
            kinds.add(Kind.REENTRY);
        }
        return kinds;
    }

    /**
     * Whether a {@code Fetch} coordinate projects catalog columns or rows: every shape but the
     * Java-side reads ({@link TargetShape.Record} / {@link TargetShape.Field}), with a bare
     * {@link TargetShape.Column} splitting on the source shape (a table-row parent projects the
     * column; a producer-record parent reads it off the in-memory record).
     */
    private static boolean catalogProjection(OutputField leaf) {
        return switch (leaf.target().shape()) {
            case TargetShape.Table _, TargetShape.Interface _, TargetShape.Union _,
                 TargetShape.Connection _ -> true;
            case TargetShape.Column _ ->
                !(leaf instanceof ChildField cf) || cf.sourceShape() == SourceShape.Table;
            case TargetShape.Record _, TargetShape.Field _ -> false;
        };
    }

    /** The gated condition / orderBy / paginate kinds a lookup or service-table leaf's own slots imply. */
    private static void addSqlSurface(Set<Kind> kinds, OutputField leaf) {
        if (!(leaf instanceof SqlGeneratingField sql)) {
            return;
        }
        if (!sql.filters().isEmpty()) {
            kinds.add(Kind.CONDITION);
        }
        if (!(sql.orderBy() instanceof OrderBySpec.None)) {
            kinds.add(Kind.ORDER_BY);
        }
        if (sql.pagination() != null) {
            kinds.add(Kind.PAGINATE);
        }
    }

    /** Spot-checks that each payload-bearing member carries the same payload the summary arm (or leaf slot) holds. */
    private static void assertPayloadsMirrorTheSummary(OutputField leaf, List<OperationMember> produced,
            String exampleId) {
        Operation op = leaf.operation();
        for (OperationMember m : produced) {
            String at = leaf.parentTypeName() + "." + leaf.name() + " in " + exampleId;
            switch (m) {
                case OperationMember.Lookup l ->
                    assertThat(l.lookupMapping())
                        .as("lookup payload at %s", at)
                        .isSameAs(((Operation.Lookup) op).lookupMapping());
                case OperationMember.ServiceCall sc ->
                    assertThat(sc.call())
                        .as("serviceCall payload at %s", at)
                        .isEqualTo(((Operation.ServiceCall) op).call());
                case OperationMember.Paginate p -> {
                    if (op instanceof Operation.Paginate summary) {
                        assertThat(p.pagination()).as("paginate payload at %s", at)
                            .isSameAs(summary.pagination());
                    }
                }
                case OperationMember.OrderBy o -> {
                    if (op instanceof Operation.Fetch f) {
                        assertThat(o.orderBy()).as("orderBy payload at %s", at).isSameAs(f.orderBy());
                    } else if (op instanceof Operation.Paginate p2) {
                        assertThat(o.orderBy()).as("orderBy payload at %s", at).isSameAs(p2.orderBy());
                    }
                }
                case OperationMember.Write w -> assertThat(writeMirrorsSummary(w, op))
                    .as("write member %s mirrors summary arm %s at %s",
                        w.getClass().getSimpleName(), op.getClass().getSimpleName(), at)
                    .isTrue();
                case OperationMember.Condition c -> {
                    if (op instanceof Operation.Fetch f && !(leaf instanceof QueryField.QueryInterfaceField)
                            && !(leaf instanceof QueryField.QueryUnionField)) {
                        assertThat(c.filters()).as("condition payload at %s", at).isEqualTo(f.filters());
                    } else if (op instanceof Operation.Paginate p2) {
                        assertThat(c.filters()).as("condition payload at %s", at).isEqualTo(p2.filters());
                    }
                }
                case OperationMember.Select _, OperationMember.Join _, OperationMember.Reentry _,
                     OperationMember.NodeResolve _, OperationMember.EntityResolve _,
                     OperationMember.Count _, OperationMember.Facet _, OperationMember.Pivot _ -> {
                    // Trigger-reference-only members: nothing to mirror.
                }
            }
        }
    }

    private static boolean writeMirrorsSummary(OperationMember.Write w, Operation op) {
        return switch (w) {
            case OperationMember.Write.Insert i ->
                op instanceof Operation.Insert s && i.input() == s.input();
            case OperationMember.Write.Upsert u ->
                op instanceof Operation.Upsert s && u.input() == s.input();
            case OperationMember.Write.Update u ->
                op instanceof Operation.Update s && u.inputArg() == s.inputArg() && u.updateRows() == s.updateRows();
            case OperationMember.Write.Delete d ->
                op instanceof Operation.Delete s && d.inputArg() == s.inputArg() && d.deleteRows() == s.deleteRows();
            case OperationMember.Write.RoutineWrite _ -> op instanceof Operation.RoutineWrite;
            case OperationMember.Write.UpdateMatching _, OperationMember.Write.DeleteMatching _ -> false;
        };
    }

    /** The modeled-but-unpopulated member kinds, kept distinct from a silently empty vocabulary. */
    @Test
    void unpopulatedKindsAreDeclaredNotAccidental() {
        // These kinds exist in the vocabulary with no producing leaf: the protocol and
        // connection members whose coordinate home is still open, and the condition-matched
        // writes. Their arms construct fine; only production is absent.
        assertThat(new OperationMember.EntityResolve().kind()).isEqualTo(Kind.ENTITY_RESOLVE);
        assertThat(new OperationMember.Count().kind()).isEqualTo(Kind.COUNT);
        assertThat(new OperationMember.Facet().kind()).isEqualTo(Kind.FACET);
        assertThat(new OperationMember.Write.UpdateMatching().kind()).isEqualTo(Kind.WRITE);
        assertThat(new OperationMember.Write.DeleteMatching().kind()).isEqualTo(Kind.WRITE);
    }

    /** {@link OperationMembers#DECLARED_SHAPES} values are internally coherent (disjoint sets enforced at construction). */
    @Test
    void declaredShapesAreWellFormed() {
        for (Map.Entry<Class<? extends OutputField>, OperationMembers.DeclaredShape> e
                : OperationMembers.DECLARED_SHAPES.entrySet()) {
            var overlap = e.getValue().required().stream()
                .filter(e.getValue().optional()::contains)
                .toList();
            assertThat(overlap)
                .as("required/optional overlap of %s", e.getKey().getSimpleName())
                .isEmpty();
        }
    }
}
