package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tier for the {@link FetchersHelperNames} resolver. The {@code decode*} namespace is keyed on
 * GraphQL type names rather than classes and so has no disambiguation to exercise; what it keeps
 * here is what the pipeline tier cannot reach, the uncollected-name refusal on both its arms and
 * the duplicate-claim check.
 */
@UnitTier
class FetchersHelperNamesTest {

    private static final String A_RECORDS = "com.acme.jooq.multischema_a.tables.records";

    @Test
    void bareResolver_answersSimpleNameForAnyClass() {
        var bare = FetchersHelperNames.bare();
        var event = ClassName.get(A_RECORDS, "EventRecord");
        assertThat(bare.createSingular(event)).isEqualTo("createEventRecord");
        assertThat(bare.decodeSingular("Event")).isEqualTo("decodeEventRecord");
    }

    @Test
    void populatedResolver_throwsForUncollectedDecodeName() {
        // A routing hole on the decode axis, both arms: a node type and a polymorphic
        // container the resolver never collected each name a helper nothing emitted.
        var names = FetchersHelperNames.of(List.of("Event"),
            Map.of("Occupant", List.of("Customer", "Staff")));
        assertThat(names.decodeSingular("Event")).isEqualTo("decodeEventRecord");
        assertThat(names.decodeContainerSingular("Occupant")).isEqualTo("decodeOccupantRecord");
        assertThatIllegalState(() -> names.decodeSingular("Note"));
        assertThatIllegalState(() -> names.decodeContainerSingular("Tenant"));
    }

    @Test
    void decodeNamespace_refusesADuplicateClaimAcrossTheTwoArms() {
        // Both decode* arms are GraphQL type names and a schema declares each name once, so this is
        // unreachable from schema input; it is the check that fires the day a naming site feeds the
        // resolver something that is not a schema type name. All three claimants are covered: the
        // single-type arm, the container arm, and the container's per-member helper.
        assertThatIllegalState(() ->
            FetchersHelperNames.of(List.of("Event"),
                Map.of("Event", List.of("Customer"))));
        assertThatIllegalState(() ->
            FetchersHelperNames.of(List.of(),
                Map.of("Occupant", List.of("Customer", "Customer"))));
    }

    private static void assertThatIllegalState(Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected an IllegalStateException");
    }
}
