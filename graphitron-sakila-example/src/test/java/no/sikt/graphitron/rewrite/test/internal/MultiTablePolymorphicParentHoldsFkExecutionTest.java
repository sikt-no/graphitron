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
 * Execution-tier coverage for parent-holds-FK correlation on multi-table polymorphic child
 * fields, and for the projection-independence the {@code ParentRowDemand} capability restores.
 *
 * <p>Gaps A and B were masked because every prior multi-table polymorphic execution query happened
 * to select a field mapping the parent key, which force-projected the columns the fetcher reads off
 * the parent row. This suite deliberately selects <em>no</em> parent-key-mapped field over three
 * shapes, so the parent SELECT must carry the demanded columns purely on the capability's say-so:
 *
 * <ul>
 *   <li><b>Cross-table parent-holds-FK single child</b> ({@code Store.contact}): the parent-side
 *       correlation column is the parent's own FK column ({@code store.address_id}), never the
 *       parent key. Store 2 seeds {@code manager_staff_id} NULL, so only the address branch matches:
 *       a deterministic single-branch assertion. Gap A.</li>
 *   <li><b>Batched list child</b> ({@code Category.childRefs} without {@code categoryId}): the
 *       DataLoader key extraction reads the parent key off the parent row; the capability projects it
 *       even when unselected. Gap B.</li>
 *   <li><b>JoinedCorrelation single child</b> ({@code Film.firstMemberViaJunction} without
 *       {@code filmId}): the healthy child-holds-FK orientation reads the parent key on the hop-0
 *       correlation; unselecting the parent key would have thrown before the capability. Gap A,
 *       parent-key orientation.</li>
 * </ul>
 */
@ExecutionTier
class MultiTablePolymorphicParentHoldsFkExecutionTest {

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
    @Test
    void crossTableParentHoldsFk_single_resolvesOffKeyParentColumn_withoutSelectingParentKey() {
        // Store.contact selects only the participant fields — NOT storeId (the store PK). The
        // single-fetch WHERE reads store.address_id off the parent record; the capability must have
        // force-projected it even though no selected field maps it. Store 2's manager_staff_id is NULL,
        // so only the StoreLocation (address) branch matches — a deterministic single result.
        var data = execute("""
            { storeById(store_id: [2]) {
                contact { __typename
                    ... on StoreLocation { rowId address }
                    ... on StoreManager { rowId firstName }
                } } }
            """);
        var stores = (List<Map<String, Object>>) data.get("storeById");
        assertThat(stores).hasSize(1);
        var contact = (Map<String, Object>) stores.get(0).get("contact");
        assertThat(contact)
            .as("store 2's contact resolves the address participant via the off-key store.address_id")
            .containsEntry("__typename", "StoreLocation")
            .containsEntry("rowId", 2)
            .containsEntry("address", "28 MySQL Boulevard");
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchedList_childRefs_batchesWithoutSelectingParentKey() {
        // Category.childRefs is a DataLoader-batched list. The key extraction reads category_id off the
        // parent row; the query selects no field mapping it (no categoryId), so the batch key would be
        // null without the capability projecting the parent key. Genre(1) collects its child categories
        // and its category_label row.
        var data = execute("""
            { categoryById(category_id: [1]) {
                childRefs { __typename
                    ... on CategoryNode { name }
                    ... on CategoryLabel { label }
                } } }
            """);
        var categories = (List<Map<String, Object>>) data.get("categoryById");
        assertThat(categories).hasSize(1);
        var childRefs = (List<Map<String, Object>>) categories.get(0).get("childRefs");
        var nodeNames = childRefs.stream()
            .filter(r -> "CategoryNode".equals(r.get("__typename")))
            .map(r -> (String) r.get("name")).toList();
        var labels = childRefs.stream()
            .filter(r -> "CategoryLabel".equals(r.get("__typename")))
            .map(r -> (String) r.get("label")).toList();
        assertThat(nodeNames).containsExactlyInAnyOrder("Action", "Animation", "Comedy");
        assertThat(labels).containsExactly("genre-label");
    }

    @SuppressWarnings("unchecked")
    @Test
    void joinedCorrelationSingle_correlatesWithoutSelectingParentKey() {
        // Film.firstMemberViaJunction is a multi-hop JoinedCorrelation single field. Its hop-0
        // (film → film_actor) correlates on film.film_id (the parent key). The query selects no filmId,
        // so the parent SELECT must project film_id purely on the capability's demand — the masking that
        // every existing JoinedCorrelation query hid by selecting filmId. Film 4 has one cast member
        // (NICK) and no inventory, so only the actor branch matches.
        var data = execute("""
            { filmById(film_id: ["4"]) {
                firstMemberViaJunction { __typename
                    ... on ActorMember { rowId name }
                    ... on FilmInventory { rowId }
                } } }
            """);
        var films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        var member = (Map<String, Object>) films.get(0).get("firstMemberViaJunction");
        assertThat(member)
            .as("film 4's firstMemberViaJunction correlates on the unselected parent key")
            .containsEntry("__typename", "ActorMember")
            .containsEntry("rowId", 2)
            .containsEntry("name", "NICK");
    }
}
