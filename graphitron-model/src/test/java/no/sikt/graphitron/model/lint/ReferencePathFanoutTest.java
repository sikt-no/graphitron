package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a field's path passes through an intermediate table, and whether that table can hold more
 * than one row per set of columns the step arriving there and the step leaving it bind, over one
 * captured graph. A PRIMARY KEY or UNIQUE constraint whose every column the two steps bind pins one
 * row: {@code COVERED}. Without one the table may repeat a row per bound set: {@code FANS_OUT}. A
 * step joined by a method, or matched to a routine's result by name, binds columns no key states,
 * so the question is declined by name. A path's last step is no intermediate and draws no row.
 *
 * <p>Which verdicts word a finding, what it says, and which fields and types it stays away from is
 * witnessed end to end by the generator's pipeline test.
 */
class ReferencePathFanoutTest {

    private static final String CONDITIONS = "no.sikt.graphitron.rewrite.test.conditions.ReferencePathConditionFixtures";

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          # film_actor's key is exactly the pair the two steps bind.
          actors: [Actor] @reference(path: [
            {key: "film_actor_film_id_fkey"},
            {key: "film_actor_actor_id_fkey"}
          ])
          # film_actor_credit carries lang_code in its key, which neither step binds.
          credits: [Actor] @reference(path: [
            {key: "film_actor_film_id_fkey"},
            {key: "film_actor_credit_pair_fkey"},
            {key: "film_actor_credit_actor_id_fkey"}
          ])
          # Ends on the many side, so film_actor is the last step and no intermediate.
          castLinks: [Cast] @reference(path: [{key: "film_actor_film_id_fkey"}])
          # The step out of film_actor joins by a method.
          bridged: [Actor] @reference(path: [
            {key: "film_actor_film_id_fkey"},
            {condition: {className: "%1$s", method: "filmActorJunctionToActor"}}
          ])
        }

        type Actor @table(name: "actor") {
          firstName: String @field(name: "first_name")
        }

        type Cast @table(name: "film_actor") {
          actorId: Int @field(name: "actor_id")
          # film_pkey is bound by the arriving key step alone, and the leaving step joins by a
          # method, so the intermediate is declined and names no covering constraint.
          players: [Cast] @reference(path: [
            {key: "film_actor_film_id_fkey"},
            {condition: {className: "%1$s", method: "filmToFilmActor"}}
          ])
        }

        type StintPerson @table(name: "stint_person") {
          personId: Int @field(name: "person_id")
          # stint's uniqueness is over the person and the date; the steps bind the person alone.
          crews: [StintCrew] @reference(path: [{key: "stint_person_fkey"}, {key: "stint_crew_fkey"}])
          # stint_single's is over the person.
          singleCrews: [StintCrew] @reference(path: [
            {key: "stint_single_person_fkey"},
            {key: "stint_single_crew_fkey"}
          ])
        }

        type StintCrew @table(name: "stint_crew") {
          personId: Int @field(name: "person_id")
        }

        type Language @table(name: "language") {
          name: String
        }

        type Query {
          films: [Film]
          casts: [Cast]
          stintPeople: [StintPerson]
          # A routine's result reaches film by matching the key's columns by name, a step no key
          # states. The routine is the producer's call, so the film step is the element at place one.
          searchLanguages(actorId: Int!, minLength: Int!): [Language]
            @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
            @reference(path: [{table: "film"}, {key: "film_language_id_fkey"}])
          # The same name-matched arrival, left by a key that would cover film_pkey on its own.
          searchCasts(actorId: Int!, minLength: Int!): [Cast]
            @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
            @reference(path: [{table: "film"}, {key: "film_actor_film_id_fkey"}])
        }
        """.formatted(CONDITIONS);

    @TempDir
    Path tmp;

    @Test
    void eachIntermediateIsJudgedOnTheColumnsItsTwoStepsBind() {
        try (var store = CapturedStore.ownStoreOfCatalogWith(tmp, "fanout", SDL,
                CatalogInput.of(TestRunContext.of()), ClasspathCorpus.entries())) {
            var rows = ReferencePathFanout.intermediates(new StoreHandle(store.dsl(), "fanout")).stream()
                .map(row -> String.join(" ", row.typeName() + "." + row.fieldName(),
                    String.valueOf(row.position()), row.table(), row.verdict().name(),
                    String.valueOf(row.coveringConstraint())))
                .toList();
            assertThat(rows).containsExactly(
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
    }

    /**
     * film_actor is entered by its key to film and left by a call bound from its actor_id, so both of
     * its key's columns are bound; films_for_actor's result is left by name and declined.
     */
    @Test
    void aRoutineStepBindsTheColumnsItsParametersRead() {
        String sdl = """
            type Film @table(name: "film") {
              title: String
              castRecentFilms(minLength: Int!): [Film!]
                @reference(path: [{table: "film_actor"}])
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: $source.actor_id, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            type Query { films: [Film] }
            """;
        try (var store = CapturedStore.ownStoreOfCatalogWith(tmp, "routine", sdl,
                CatalogInput.of(TestRunContext.of()), ClasspathCorpus.entries())) {
            var rows = ReferencePathFanout.intermediates(new StoreHandle(store.dsl(), "routine")).stream()
                .map(row -> String.join(" ", row.typeName() + "." + row.fieldName(),
                    String.valueOf(row.position()), row.table(), row.verdict().name(),
                    String.valueOf(row.coveringConstraint())))
                .toList();
            assertThat(rows).containsExactly(
                "Film.castRecentFilms 0 film_actor COVERED film_actor_pkey",
                "Film.castRecentFilms 1 films_for_actor UNDECIDABLE_NAME_MATCH_HOP null");
        }
    }
}
