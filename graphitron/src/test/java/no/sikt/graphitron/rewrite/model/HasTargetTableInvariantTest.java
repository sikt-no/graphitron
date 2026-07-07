package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R232 invariant: every {@link JoinStep} permit implements {@link JoinStep.HasTargetTable}.
 * This is what lets {@link no.sikt.graphitron.rewrite.generators.JoinPathEmitter},
 * {@link no.sikt.graphitron.rewrite.generators.InlineTableFieldEmitter}, and the split-rows
 * prelude read {@code targetTable()} uniformly through one capability cast without an
 * arm-specific sealed switch.
 *
 * <p>Pinned as a meta-test so a future {@code JoinStep} permit added without
 * {@code HasTargetTable} would fail at compile time anyway, but the failure mode is loud
 * and on the right axis. Replacing the assertions with a direct reflection scan over the
 * sealed permits also surfaces the rule when a reader is trying to understand the cast
 * downstream.
 */
@UnitTier
class HasTargetTableInvariantTest {

    @Test
    void everyJoinStepPermitImplementsHasTargetTable() {
        Class<?>[] permits = JoinStep.class.getPermittedSubclasses();
        assertThat(permits)
            .as("JoinStep is sealed; the meta-test expects a non-empty permit list")
            .isNotEmpty();
        Stream.of(permits).forEach(permit ->
            assertThat(JoinStep.HasTargetTable.class)
                .as("JoinStep permit " + permit.getName() + " must implement HasTargetTable so "
                    + "emitter sites can read targetTable() uniformly")
                .isAssignableFrom(permit));
    }

    @Test
    void everyJoinStepPermitIsConcreteOrSealed() {
        // Defensive: assert the permit list is the final closed set today (the two-axis Hop,
        // plus the transitional LiftedHop that R431 retires). A new permit lands by editing
        // JoinStep's permit list, which is the right place to surface a wider audit (validator
        // coverage, emitter dispatch, model tests).
        Class<?>[] permits = JoinStep.class.getPermittedSubclasses();
        assertThat(permits)
            .extracting(Class::getSimpleName)
            .containsExactlyInAnyOrder("Hop", "LiftedHop");
        Stream.of(permits).forEach(permit ->
            assertThat(Modifier.isFinal(permit.getModifiers()) || permit.isSealed() || permit.isRecord())
                .as(permit.getName() + " should be a record or sealed leaf")
                .isTrue());
    }
}
