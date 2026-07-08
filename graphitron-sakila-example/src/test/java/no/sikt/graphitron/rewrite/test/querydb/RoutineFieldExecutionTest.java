package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R300 execution-tier proof for {@code @routine}: a real {@code RETURNS TABLE} function in the DB
 * backs a root list field, invoked end-to-end with its IN parameters bound from GraphQL arguments.
 * This is the proof that the generated {@code Routines.<method>(<bound args>)} call and the
 * FROM-attach actually run and return rows, and that selection narrowing projects only the columns
 * the query selected.
 *
 * <p>R435 extends the proof to the child-positioned {@code @routine} (the correlated single-node
 * chain): {@code Actor.films} rides the inline correlated multiset whose FROM source is
 * {@code Routines.filmsForActor(parent.ACTOR_ID, DSL.val(minLength))}, asserting per-parent
 * correlation and the mixed column/argument binding.
 *
 * <p>R435's routine-then-hops chain is proven by {@code Query.recentFilmsForActor}: the routine
 * result is the FROM source and the {@code @reference} hop lands the terminus on the {@code film}
 * catalog table, joined on the name-matched target PK ({@code source.FILM_ID = film.FILM_ID} —
 * a routine result carries no FK). Projecting film-table-only columns proves the hop keyed
 * correctly.
 */
@ExecutionTier
class RoutineFieldExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        String localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            dsl = DSL.using(localUrl,
                System.getProperty("test.db.username", "postgres"),
                System.getProperty("test.db.password", "postgres"));
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void tableValuedRoutineReturnsRowsWithArgumentsBound() {
        var data = execute("""
            { tilganger(env: "prod", serviceId: "svc", feideId: "feide-123") {
                organisasjonskode
                rollekode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("tilganger");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("organisasjonskode")).containsExactly(184, 185);
        assertThat(rows).extracting(r -> r.get("rollekode")).containsExactly("admin", "user");
    }

    @Test
    void selectionNarrowingProjectsOnlySelectedColumn() {
        // The function body executes in full, but the wrapping SELECT projects only the routine-result
        // column the query selected. The unselected `rollekode` must not appear in the response map.
        var data = execute("""
            { tilganger(env: "prod", serviceId: "svc", feideId: "feide-123") {
                organisasjonskode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("tilganger");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r).containsKey("organisasjonskode");
            assertThat(r).doesNotContainKey("rollekode");
        });
        assertThat(rows).extracting(r -> r.get("organisasjonskode")).containsExactly(184, 185);
    }

    @Test
    void correlatedChildRoutineReturnsPerParentRows() {
        // R435: the child-positioned @routine (single-node chain, implicit head). pActorId is fed
        // from each parent Actor row's actor_id (columnMapping), pMinLength from the GraphQL
        // argument (argMapping). Seeded casts: PENELOPE(1) -> films 1,2,3; NICK(2) -> 1,4;
        // ED(3) -> 2,5. With minLength: 0 every cast film comes back, correlated per parent.
        var data = execute("""
            { allActors {
                firstName
                films(minLength: 0) { filmId title }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(actors).hasSize(3);
        assertThat(filmIdsOf(actors, "PENELOPE")).containsExactly(1, 2, 3);
        assertThat(filmIdsOf(actors, "NICK")).containsExactly(1, 4);
        assertThat(filmIdsOf(actors, "ED")).containsExactly(2, 5);
    }

    @Test
    void correlatedChildRoutineBindsArgumentAlongsideColumn() {
        // The mixed call: pMinLength narrows inside the function body (film lengths: 1->86,
        // 2->48, 3->50, 4->117, 5->169), proving the argument-sourced Field binding reaches the
        // routine alongside the column-sourced correlation.
        var data = execute("""
            { allActors {
                firstName
                films(minLength: 50) { filmId }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(filmIdsOf(actors, "PENELOPE")).containsExactly(1, 3); // film 2 (48) filtered out
        assertThat(filmIdsOf(actors, "NICK")).containsExactly(1, 4);
        assertThat(filmIdsOf(actors, "ED")).containsExactly(5);          // film 2 (48) filtered out
    }

    @Test
    void rootRoutineThenHopsChainJoinsOutOfRoutineResult() {
        // R435: the root routine-then-hops chain. The routine narrows to PENELOPE(1)'s films of
        // length >= 50 (films 1 and 3); the name-matched hop out of the routine result lands on
        // the film table, and `description` exists ONLY there (the routine result exposes just
        // film_id and title) — a mis-keyed or missing hop cannot produce these values.
        var data = execute("""
            { recentFilmsForActor(actorId: 1, minLength: 50) {
                filmId
                title
                description
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("recentFilmsForActor");
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactlyInAnyOrder(1, 3);
        assertThat(films).extracting(f -> f.get("description"))
            .containsExactlyInAnyOrder("A Epic Drama", "A Quirky Comedy");
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> filmIdsOf(List<Map<String, Object>> actors, String firstName) {
        return actors.stream()
            .filter(a -> firstName.equals(a.get("firstName")))
            .flatMap(a -> ((List<Map<String, Object>>) a.get("films")).stream())
            .map(f -> (Integer) f.get("filmId"))
            .toList();
    }

    private Map<String, Object> execute(String query) {
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
