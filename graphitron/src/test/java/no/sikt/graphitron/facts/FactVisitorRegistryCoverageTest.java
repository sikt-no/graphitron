package no.sikt.graphitron.facts;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact-visitor registry's coverage pins, mirroring the lint registry's
 * no-silent-default discipline ({@code LintRuleRegistryCoverageTest}): every visitor the sealed
 * contract permits is registered exactly once, and the subject-kind partition is declared with
 * no overlap or gap. What these pins cannot see, an empty relation from a wired visitor, is
 * each fact's own population pin's job (the pagination fact's lives in
 * {@code PaginationFactPipelineTest}).
 */
@UnitTier
class FactVisitorRegistryCoverageTest {

    @Test
    void everyPermittedVisitorIsRegisteredExactlyOnce() {
        var registered = FactVisitors.builtIn().stream()
            .map(v -> v.getClass().getName())
            .collect(Collectors.toList());
        var permitted = java.util.Arrays.stream(FactVisitor.class.getPermittedSubclasses())
            .map(Class::getName)
            .collect(Collectors.toSet());
        assertThat(registered)
            .as("the registry and the sealed contract are two views of one visitor set: a permit"
                + " without a registration is a gathered fact production never runs, and a"
                + " duplicate registration double-gathers")
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(permitted);
    }

    @Test
    void subscribedAndNotGatheredKindsPartitionAllSubjectKinds() {
        var subscribed = FactVisitors.builtIn().stream()
            .flatMap(v -> v.kinds().stream())
            .collect(Collectors.toCollection(HashSet::new));

        var overlap = EnumSet.copyOf(subscribed);
        overlap.retainAll(FactVisitors.NOT_GATHERED);
        assertThat(overlap)
            .as("a kind is subscribed or deliberately waived, never both")
            .isEmpty();

        var union = new HashSet<>(subscribed);
        union.addAll(FactVisitors.NOT_GATHERED);
        assertThat(union)
            .as("every dispatched subject kind is claimed by a visitor or waived in"
                + " FactVisitors.NOT_GATHERED; a new dispatch position must pick a side")
            .containsExactlyInAnyOrder(FactSubjectKind.values());
    }
}
