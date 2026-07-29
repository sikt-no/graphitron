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
     * deliberately: output-type and input-member subjects are dispatched so a future type-grain
     * or input-grain fact subscribes instead of growing a second traversal, and stay waived
     * until one does.
     */
    public static final Set<FactSubjectKind> NOT_GATHERED = Set.of(
        FactSubjectKind.OUTPUT_TYPE,
        FactSubjectKind.INPUT_OBJECT_FIELD);

    /** One instance per registered visitor; a fresh set per gather (visitors accumulate state). */
    public static List<FactVisitor> builtIn() {
        return List.of(new PaginationFactVisitor());
    }
}
