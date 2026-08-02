package no.sikt.graphitron.facts;

import java.util.List;
import java.util.Set;

/**
 * The fact-visitor registry: {@link #builtIn()} is the one list a gather runs, and
 * {@link #NOT_GATHERED} is the declared waiver set for subject kinds no visitor subscribes.
 * The registry-coverage meta-test asserts the two-sided contract (every visitor registered
 * exactly once and matching {@link FactVisitor}'s sealed permits; subscribed kinds union
 * {@code NOT_GATHERED} partition {@link FactSubjectKind} with no overlap or gap), mirroring the
 * lint engine's registry discipline; what the partition test cannot see (an empty relation) is
 * each fact's own population pin's job.
 */
public final class FactVisitors {

    private FactVisitors() {}

    /**
     * Subject kinds the traversal dispatches but no fact gathers from yet, each waived
     * deliberately: the output-type subject is dispatched so a future type-grain fact
     * subscribes instead of growing a second traversal, and stays waived until one does.
     * The input-member subject left this set when the condition and lookup triggers
     * subscribed it.
     */
    public static final Set<FactSubjectKind> NOT_GATHERED = Set.of(
        FactSubjectKind.OUTPUT_TYPE);

    /** One instance per registered visitor; a fresh set per gather (visitors accumulate state). */
    public static List<FactVisitor> builtIn() {
        return List.of(new PaginationFactVisitor(), new ConditionFactVisitor(),
            new OrderByFactVisitor(), new LookupFactVisitor(), new ServiceFactVisitor(),
            new WriteFactVisitor());
    }
}
