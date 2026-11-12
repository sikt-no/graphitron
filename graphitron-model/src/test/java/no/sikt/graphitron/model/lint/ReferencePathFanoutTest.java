package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a field's path passes through an intermediate table, and whether that table can hold more
 * than one row per set of columns the step arriving there and the step leaving it bind, over one
 * captured graph. A PRIMARY KEY or UNIQUE constraint whose every column the two steps bind pins one
 * row: {@code COVERED}. Without one the table may repeat a row per bound set: {@code FANS_OUT}. A
 * step joined by a method, or matched to a routine's result by name, binds columns no key states,
 * so the question is declined by name. A path's last step is no intermediate and draws no row.
 * The paths are the {@code field-path-fanout} and {@code field-path-routine-step} fact documents'.
 *
 * <p>Which verdicts word a finding, what it says, and which fields and types it stays away from is
 * witnessed end to end by the generator's pipeline test.
 */
class ReferencePathFanoutTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void eachIntermediateIsJudgedOnTheColumnsItsTwoStepsBind() {
        assertThat(intermediates("scenarios/field-path-fanout")).containsExactly(
            "Cast.players 0 film UNDECIDABLE_CONDITION_HOP null",
            "Film.actors 0 film_actor COVERED film_actor_pkey",
            "Film.bridged 0 film_actor UNDECIDABLE_CONDITION_HOP null",
            "Film.credits 0 film_actor COVERED film_actor_pkey",
            "Film.credits 1 film_actor_credit FANS_OUT null",
            "Query.searchCasts 1 film UNDECIDABLE_NAME_MATCH_HOP null",
            "Query.searchLanguages 1 film UNDECIDABLE_NAME_MATCH_HOP null",
            "StintPerson.crews 0 stint FANS_OUT null",
            "StintPerson.singleCrews 0 stint_single COVERED stint_single_person_key");
    }

    /**
     * film_actor is entered by its key to film and left by a call bound from its actor_id, so both of
     * its key's columns are bound; films_for_actor's result is left by name and declined. A routine
     * ending a path is its last step and no intermediate.
     */
    @Test
    void aRoutineStepBindsTheColumnsItsParametersRead() {
        assertThat(intermediates("scenarios/field-path-routine-step")).containsExactly(
            "Film.castRecentFilms 0 film_actor COVERED film_actor_pkey",
            "Film.castRecentFilms 1 films_for_actor UNDECIDABLE_NAME_MATCH_HOP null");
    }

    private static List<String> intermediates(String path) {
        return FACTS.read(path, graph -> ReferencePathFanout.intermediates(graph.handle()).stream()
            .map(row -> String.join(" ", row.typeName() + "." + row.fieldName(),
                String.valueOf(row.position()), row.table(), row.verdict().name(),
                String.valueOf(row.coveringConstraint())))
            .toList());
    }
}
