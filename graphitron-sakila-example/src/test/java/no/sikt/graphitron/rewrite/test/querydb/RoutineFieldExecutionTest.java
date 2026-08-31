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
 * Execution-tier proof for {@code @routine}: a real {@code RETURNS TABLE} function in the DB
 * backs GraphQL fields end-to-end. {@code Query.tilganger} proves the generated
 * {@code Routines.<method>(<bound args>)} call and the FROM-attach run and return rows, with IN
 * parameters bound from GraphQL arguments, and that selection narrowing projects only the columns
 * the query selected.
 *
 * <p>The remaining chain shapes are proven per field; each test's comment states what its
 * assertions pin down. A routine result carries no FK, so hops out of one join on the
 * name-matched target PK.
 * <ul>
 *   <li>{@code Actor.films}: correlated single-node child (inline multiset, mixed column/argument binding)</li>
 *   <li>{@code Query.recentFilmsForActor}: root routine-then-hops</li>
 *   <li>{@code Actor.recentFilms}: child routine-then-hops (lateral head, name-matched hop out)</li>
 *   <li>{@code Film.castFilms}: hops-then-routine ({@code columnMapping} binds against the previous node)</li>
 *   <li>{@code Film.castRecentFilms}: the sandwich (hops in, CROSS JOIN LATERAL, name-matched hop back out)</li>
 *   <li>{@code Actor.filmsSplit} / {@code Actor.recentFilmsSplit}: batched keyed re-query ({@code @splitQuery})</li>
 * </ul>
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
        // The child-positioned @routine (single-node chain, implicit head). pActorId is fed
        // from each parent Actor row's actor_id (columnMapping), pMinLength from the GraphQL
        // argument (argMapping). Seeded casts: PENELOPE(1) -> films 1,2,3; NICK(2) -> 1,4;
        // ED(3) -> 2,5; JOAN(4) is cast in nothing, so her correlated child is empty rather
        // than absent. With minLength: 0 every cast film comes back, correlated per parent.
        var data = execute("""
            { allActors {
                firstName
                films(minLength: 0) { filmId title }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(actors).hasSize(4);
        assertThat(filmIdsOf(actors, "PENELOPE")).containsExactly(1, 2, 3);
        assertThat(filmIdsOf(actors, "NICK")).containsExactly(1, 4);
        assertThat(filmIdsOf(actors, "ED")).containsExactly(2, 5);
        assertThat(filmIdsOf(actors, "JOAN")).isEmpty();
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
    void correlatedChildRoutineBindsArgumentThroughDotPath() {
        // The same mixed call with pMinLength authored as a dot-path into a wrapper input. At a
        // correlated child position the descent roots on the field's own SelectedField, not the
        // ancestor fetcher's env, so this is the fork Actor.films' coverage cannot reach. Same
        // expected rows as correlatedChildRoutineBindsArgumentAlongsideColumn: reading the outer
        // input map instead of the nested value would bind null and drop the length filter.
        var data = execute("""
            { allActors {
                firstName
                filmsNested(filter: { minLength: 50 }) { filmId }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(filmIdsOfField(actors, "PENELOPE", "filmsNested")).containsExactly(1, 3);
        assertThat(filmIdsOfField(actors, "NICK", "filmsNested")).containsExactly(1, 4);
        assertThat(filmIdsOfField(actors, "ED", "filmsNested")).containsExactly(5);
    }

    @Test
    void rootRoutineThenHopsChainJoinsOutOfRoutineResult() {
        // The root routine-then-hops chain. The routine narrows to PENELOPE(1)'s films of
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
        // Exact order, not any-order: the chain terminates on the film catalog table, so the
        // ordinary primary-key fallback supplies ORDER BY film.film_id with no schema edit.
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactly(1, 3);
        assertThat(films).extracting(f -> f.get("description"))
            .containsExactly("A Epic Drama", "A Quirky Comedy");
    }

    @Test
    void routineTerminusListOrdersByItsAuthoredDefaultOrder() {
        // The routine terminus has no primary key, so the authored @defaultOrder over the
        // function's own result columns is the whole ordering contract. Row order is behaviour,
        // and behaviour only closes at this tier: a dropped ORDER BY leaves the rows in whatever
        // order the function body produced them.
        var data = execute("""
            { tilganger(env: "prod", serviceId: "svc", feideId: "feide-123") {
                organisasjonskode
                rollekode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("tilganger");
        assertThat(rows).extracting(r -> r.get("organisasjonskode")).containsExactly(184, 185);
    }

    @Test
    void developerConditionFiltersTheRoutineResult() {
        // @condition on a routine-backed field: the predicate lands in the WHERE of the same
        // statement whose FROM is the function call. The unfiltered sibling returns both roles.
        var data = execute("""
            { tilgangerAdmin(env: "prod", serviceId: "svc", feideId: "feide-123") {
                organisasjonskode
                rollekode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("tilgangerAdmin");
        assertThat(rows).extracting(r -> r.get("rollekode")).containsExactly("admin");
    }

    @Test
    void routineTerminusConnectionPagesAndCountsOverTheFunctionCall() {
        // Keyset pagination with the function in the FROM. The cursor columns are the authored
        // @defaultOrder over the function's own result columns; totalCount counts the function
        // call, so it sees both rows while the page carries one.
        var first = execute("""
            { tilgangerConnection(env: "prod", serviceId: "svc", feideId: "feide-123", first: 1) {
                totalCount
                pageInfo { hasNextPage endCursor }
                nodes { organisasjonskode rollekode }
              } }
            """);
        @SuppressWarnings("unchecked")
        Map<String, Object> conn = (Map<String, Object>) first.get("tilgangerConnection");
        assertThat(conn).containsEntry("totalCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page1 = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(page1).extracting(r -> r.get("organisasjonskode")).containsExactly(184);
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo).containsEntry("hasNextPage", true);

        // The second page proves the seek predicate composed against the function's own columns
        // rather than being dropped: a dropped seek returns the first row again.
        var second = execute("""
            { tilgangerConnection(env: "prod", serviceId: "svc", feideId: "feide-123",
                                  first: 1, after: "%s") {
                nodes { organisasjonskode rollekode }
              } }
            """.formatted(pageInfo.get("endCursor")));
        @SuppressWarnings("unchecked")
        Map<String, Object> conn2 = (Map<String, Object>) second.get("tilgangerConnection");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(page2).extracting(r -> r.get("organisasjonskode")).containsExactly(185);
    }

    @Test
    void catalogTerminusConnectionCountsTheJoinedChainNotTheTerminusAlone() {
        // The count source for a chain with hops is the joined table expression. Counting the
        // terminus alone would count every film in the catalog, so this number is the assertion:
        // PENELOPE(1) has two films of length >= 50, and the seed carries five films in all.
        var data = execute("""
            { recentFilmsForActorConnection(actorId: 1, minLength: 50, first: 1) {
                totalCount
                nodes { filmId description }
              } }
            """);
        @SuppressWarnings("unchecked")
        Map<String, Object> conn = (Map<String, Object>) data.get("recentFilmsForActorConnection");
        assertThat(conn).containsEntry("totalCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).extracting(r -> r.get("filmId")).containsExactly(1);
        assertThat(nodes).extracting(r -> r.get("description")).containsExactly("A Epic Drama");
    }

    @Test
    void orderByArgumentSortsTheRoutineResultBothWays() {
        // The argument order resolves against the terminus (the function result) and reverses
        // on direction, which is what proves the emitted helper drives the statement's ORDER BY
        // rather than the authored @defaultOrder falling through.
        var ascending = execute("""
            { tilgangerSortert(env: "prod", serviceId: "svc", feideId: "feide-123",
                               sort: {field: ROLLEKODE, direction: ASC}) {
                rollekode
            } }
            """);
        var descending = execute("""
            { tilgangerSortert(env: "prod", serviceId: "svc", feideId: "feide-123",
                               sort: {field: ROLLEKODE, direction: DESC}) {
                rollekode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> asc = (List<Map<String, Object>>) ascending.get("tilgangerSortert");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> desc = (List<Map<String, Object>>) descending.get("tilgangerSortert");
        assertThat(asc).extracting(r -> r.get("rollekode")).containsExactly("admin", "user");
        assertThat(desc).extracting(r -> r.get("rollekode")).containsExactly("user", "admin");
    }

    @Test
    void childRoutineThenHopsChainJoinsOutOfRoutineResultPerParent() {
        // Routine-then-hops at a child position: the lateral routine call heads each
        // actor's chain (correlated on that row's actor_id) and the name-matched hop lands on
        // the film table. `description` exists only there, so a mis-keyed hop cannot pass;
        // per-parent narrowing proves the correlation reaches the lateral call.
        var data = execute("""
            { allActors {
                firstName
                recentFilms(minLength: 50) { filmId description }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(fieldOf(actors, "PENELOPE", "recentFilms", "filmId")).containsExactly(1, 3);
        assertThat(fieldOf(actors, "PENELOPE", "recentFilms", "description"))
            .containsExactly("A Epic Drama", "A Quirky Comedy");
        assertThat(fieldOf(actors, "NICK", "recentFilms", "filmId")).containsExactly(1, 4);
        assertThat(fieldOf(actors, "ED", "recentFilms", "filmId")).containsExactly(5); // film 2 (48) filtered out
    }

    @Test
    void childHopsThenRoutineChainBindsColumnMappingAgainstPreviousNode() {
        // Hops-then-routine: the FK hop reaches the film_actor junction first, so
        // pActorId is fed from film_actor.actor_id (the previous node), NOT the implicit head.
        // For film 1 the cast is PENELOPE(1) and NICK(2): films_for_actor(1, 50) -> {1, 3},
        // films_for_actor(2, 50) -> {1, 4}; the multiset concatenates per junction row and
        // @defaultOrder(film_id) sorts the merged set. A head-bound pActorId (film_id = 1 for
        // every junction row) would instead repeat PENELOPE's set twice.
        var data = execute("""
            { films {
                filmId
                castFilms(minLength: 50) { filmId }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(nestedOf(films, 1, "castFilms", "filmId")).containsExactly(1, 1, 3, 4);
        assertThat(nestedOf(films, 5, "castFilms", "filmId")).containsExactly(5); // cast: ED only
    }

    @Test
    void childSandwichChainJoinsBackOutToCatalogTerminus() {
        // The sandwich: film -> film_actor (FK hop), CROSS JOIN LATERAL
        // films_for_actor(fa.actor_id, 50), name-matched hop back onto film. The projected
        // `description` exists only on the film table, proving the tail hop out of the routine
        // result; the row multiset mirrors castFilms' merged cast sets.
        var data = execute("""
            { films {
                filmId
                castRecentFilms(minLength: 50) { filmId description }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(nestedOf(films, 1, "castRecentFilms", "filmId")).containsExactly(1, 1, 3, 4);
        assertThat(nestedOf(films, 1, "castRecentFilms", "description")).containsExactly(
            "A Epic Drama", "A Epic Drama", "A Quirky Comedy", "A Classic Romance");
    }

    @Test
    void splitRoutineChildBatchesByBoundColumns() {
        // Batched form: filmsSplit rides the DataLoader keyed re-query. The batch key IS
        // the routine's column-bound input (actor_id), lifted into the parentInput VALUES table;
        // the CROSS JOIN LATERAL call reads it off parentInput with no correlation JOIN. The
        // per-parent scatter (by __idx__) must reproduce exactly the inline form's rows.
        var data = execute("""
            { allActors {
                firstName
                filmsSplit(minLength: 50) { filmId }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(fieldOf(actors, "PENELOPE", "filmsSplit", "filmId")).containsExactly(1, 3);
        assertThat(fieldOf(actors, "NICK", "filmsSplit", "filmId")).containsExactly(1, 4);
        assertThat(fieldOf(actors, "ED", "filmsSplit", "filmId")).containsExactly(5);
    }

    @Test
    void splitRoutineThenHopsChainJoinsOutInsideBatchQuery() {
        // Batched routine-then-hops: the name-matched hop out of the routine result runs
        // inside the batch query, after the lateral. `description` exists only on film, so a
        // mis-keyed hop cannot pass; per-parent scatter proves the batch key correlation.
        var data = execute("""
            { allActors {
                firstName
                recentFilmsSplit(minLength: 50) { filmId description }
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actors = (List<Map<String, Object>>) data.get("allActors");
        assertThat(fieldOf(actors, "PENELOPE", "recentFilmsSplit", "filmId")).containsExactly(1, 3);
        assertThat(fieldOf(actors, "PENELOPE", "recentFilmsSplit", "description"))
            .containsExactly("A Epic Drama", "A Quirky Comedy");
        assertThat(fieldOf(actors, "NICK", "recentFilmsSplit", "filmId")).containsExactly(1, 4);
        assertThat(fieldOf(actors, "ED", "recentFilmsSplit", "filmId")).containsExactly(5);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> fieldOf(List<Map<String, Object>> actors, String firstName,
            String listField, String column) {
        return actors.stream()
            .filter(a -> firstName.equals(a.get("firstName")))
            .flatMap(a -> ((List<Map<String, Object>>) a.get(listField)).stream())
            .map(f -> f.get(column))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> nestedOf(List<Map<String, Object>> films, int filmId,
            String listField, String column) {
        return films.stream()
            .filter(f -> Integer.valueOf(filmId).equals(f.get("filmId")))
            .flatMap(f -> ((List<Map<String, Object>>) f.get(listField)).stream())
            .map(r -> r.get(column))
            .toList();
    }

    private static List<Integer> filmIdsOf(List<Map<String, Object>> actors, String firstName) {
        return filmIdsOfField(actors, firstName, "films");
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> filmIdsOfField(List<Map<String, Object>> actors, String firstName,
            String fieldName) {
        return actors.stream()
            .filter(a -> firstName.equals(a.get("firstName")))
            .flatMap(a -> ((List<Map<String, Object>>) a.get(fieldName)).stream())
            .map(f -> (Integer) f.get("filmId"))
            .toList();
    }

    private Map<String, Object> execute(String query) {
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
