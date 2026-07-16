package no.sikt.graphitron.rewrite.test.internal;

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
 * R458 slice-2 (multi-hop FK chains) and slice-3 (condition correlation) execution-tier coverage for
 * {@code @referenceFor} on a <em>multi-table</em> polymorphic child field: the
 * {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation.JoinedCorrelation} arm the classifier
 * lowers a richer-than-single-hop route to, emitted by {@code MultiTablePolymorphicEmitter} in all
 * three cardinality forms.
 *
 * <p>Fixtures ({@code schema.graphqls}), all on the {@code Film} parent:
 *
 * <ul>
 *   <li><b>Slice 2 — multi-hop.</b> {@code Film.firstMemberViaJunction} (single),
 *       {@code Film.membersViaJunction} (list) and {@code Film.membersViaJunctionConnection}
 *       (connection) return {@code FilmCastMember}. Its {@code ActorMember} participant (backed by
 *       {@code actor}) states the two-hop route {@code film -> film_actor -> actor} with
 *       {@code @referenceFor}; the emitter bridges the {@code film_actor} junction back toward the
 *       parent and value-binds hop-0 ({@code film_actor.film_id}) to the parent {@code film_id}. The
 *       {@code FilmInventory} participant ({@code inventory}) keeps the field multi-table via
 *       auto-discovery ({@code inventory.film_id -> film}).</li>
 *   <li><b>Slice 3 — condition.</b> {@code Film.firstByCondition} (single) and
 *       {@code Film.castByCondition} (list) return {@code FilmCondRef}. Its {@code ActorCondMember}
 *       participant states a {@code {condition:}} route ({@code filmActorsViaCondition}, an
 *       {@code EXISTS} over {@code film_actor}); the emitter joins the parent {@code film} table
 *       aliased and applies the two-arg condition between the parent alias and the actor alias,
 *       binding the parent {@code film_id}. {@code InvCondMember} auto-discovers its FK.</li>
 * </ul>
 *
 * <p>Seed data (init.sql): {@code film_actor} links film 1 to actors PENELOPE(1) and NICK(2), and
 * film 4 to actor NICK(2) only; {@code inventory} covers films 1-3 but not film 4. So film 1
 * exercises both participants and multi-row fan-out; film 4 gives a deterministic single-branch
 * (single-cardinality) assertion where only the actor participant matches.
 */
@ExecutionTier
class MultiTablePolymorphicJoinedCorrelationExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filmById(String id, String selection) {
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"" + id + "\"]) { filmId " + selection + " } }");
        var list = (List<Map<String, Object>>) data.get("filmById");
        assertThat(list).as("filmById(%s) returns exactly one row", id).hasSize(1);
        return list.get(0);
    }

    private static final String CAST_SELECTION =
        "{ __typename ... on ActorMember { rowId name } ... on FilmInventory { rowId } }";
    private static final String COND_SELECTION =
        "{ __typename ... on ActorCondMember { rowId name } ... on InvCondMember { rowId } }";

    // ===== Slice 2: multi-hop FK chain (film -> film_actor -> actor) =====

    @SuppressWarnings("unchecked")
    @Test
    void multiHopList_bridgesJunction_bothParticipantsContribute() {
        // Film 1: the ActorMember branch bridges film_actor to reach PENELOPE(1) and NICK(2); the
        // FilmInventory branch auto-discovers inventory 1. A flipped junction orientation or a
        // dropped hop-0 correlation would return the wrong actors, or all actors.
        var film = filmById("1", "membersViaJunction " + CAST_SELECTION);
        var members = (List<Map<String, Object>>) film.get("membersViaJunction");

        var actorNames = members.stream()
            .filter(r -> "ActorMember".equals(r.get("__typename")))
            .map(r -> (String) r.get("name")).toList();
        var inventoryIds = members.stream()
            .filter(r -> "FilmInventory".equals(r.get("__typename")))
            .map(r -> (Integer) r.get("rowId")).toList();

        assertThat(actorNames)
            .as("film 1's cast via the film_actor junction (multi-hop ActorMember branch)")
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
        assertThat(inventoryIds)
            .as("film 1's inventory via the auto-discovered FK (FilmInventory branch)")
            .containsExactly(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void multiHopSingle_deterministicWhenOnlyActorBranchMatches() {
        // Film 4 has one cast member (NICK, actor 2) and no inventory, so the single-cardinality
        // field resolves deterministically to the multi-hop ActorMember branch.
        var film = filmById("4", "firstMemberViaJunction " + CAST_SELECTION);
        var member = (Map<String, Object>) film.get("firstMemberViaJunction");
        assertThat(member)
            .containsEntry("__typename", "ActorMember")
            .containsEntry("rowId", 2)
            .containsEntry("name", "NICK");
    }

    @SuppressWarnings("unchecked")
    @Test
    void multiHopConnection_paginatesMultiTableDispatch() {
        // The windowed-CTE connection arm over the same multi-hop route: film 1 yields three edges
        // (PENELOPE, NICK, inventory 1) within the first page.
        var film = filmById("1",
            "membersViaJunctionConnection(first: 5) { edges { node " + CAST_SELECTION + " } totalCount }");
        var conn = (Map<String, Object>) film.get("membersViaJunctionConnection");
        var edges = (List<Map<String, Object>>) conn.get("edges");
        assertThat(conn.get("totalCount")).isEqualTo(3);

        var typenames = edges.stream()
            .map(e -> (String) ((Map<String, Object>) e.get("node")).get("__typename")).toList();
        assertThat(typenames)
            .as("both participants dispatch through the connection page")
            .contains("ActorMember", "FilmInventory");
        var actorNames = edges.stream()
            .map(e -> (Map<String, Object>) e.get("node"))
            .filter(n -> "ActorMember".equals(n.get("__typename")))
            .map(n -> (String) n.get("name")).toList();
        assertThat(actorNames).containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    // ===== Slice 3: condition correlation (parent film aliased, EXISTS predicate) =====

    @SuppressWarnings("unchecked")
    @Test
    void conditionList_joinsParentOnPredicate_bothParticipantsContribute() {
        // Film 1: ActorCondMember correlates by filmActorsViaCondition (EXISTS over film_actor),
        // reaching PENELOPE and NICK; InvCondMember auto-discovers inventory 1.
        var film = filmById("1", "castByCondition " + COND_SELECTION);
        var members = (List<Map<String, Object>>) film.get("castByCondition");

        var actorNames = members.stream()
            .filter(r -> "ActorCondMember".equals(r.get("__typename")))
            .map(r -> (String) r.get("name")).toList();
        var inventoryIds = members.stream()
            .filter(r -> "InvCondMember".equals(r.get("__typename")))
            .map(r -> (Integer) r.get("rowId")).toList();

        assertThat(actorNames)
            .as("film 1's cast via the condition predicate (ActorCondMember branch)")
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
        assertThat(inventoryIds).containsExactly(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void conditionSingle_deterministicWhenOnlyActorBranchMatches() {
        // Film 4: only the condition-correlated actor branch matches (NICK), no inventory.
        var film = filmById("4", "firstByCondition " + COND_SELECTION);
        var member = (Map<String, Object>) film.get("firstByCondition");
        assertThat(member)
            .containsEntry("__typename", "ActorCondMember")
            .containsEntry("rowId", 2)
            .containsEntry("name", "NICK");
    }
}
