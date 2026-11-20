package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Test fixtures for the pipeline-tier coverage of the binding <em>walk</em> grounding a child backing
 * class through a parent accessor. Each top-level parent is the reflected return type of a
 * {@code DummyService.r461*} producer; its fields ground child SDL types through the walk's probe.
 *
 * <p>What is left is the field-argument case: an argument-bearing SDL field grounding through a
 * getter that takes the argument.
 */
public final class R461AccessorFixtures {

    private R461AccessorFixtures() {}

    /** Neutral child (a record, so a {@code JavaRecordType} when grounded). */
    public record SimpleChild(String tag) {}

    /** Per-argument accessor; an argument-bearing SDL field grounds through it. */
    public static final class PerArgParent {
        public SimpleChild getChild(String x) { return null; }
    }
}
