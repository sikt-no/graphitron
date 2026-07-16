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
 * R458 slice-1 execution-tier coverage for self-referencing foreign-key orientation on a
 * <em>multi-table</em> polymorphic child field (the {@code @referenceFor} path through
 * {@code MultiTablePolymorphicEmitter}). This is the case R458's roadmap flags as
 * "must be pinned by an execution test; both columns live on the same table and a flipped
 * orientation is silently wrong data", and it is the regression guard for the R458 review finding:
 * {@code FieldBuilder.resolveChildPolymorphicJoinPaths} used to resolve every {@code @referenceFor}
 * route with a hardcoded single-valued orientation hint, so a list/connection child field with a
 * same-table self-FK route silently returned the wrong rows.
 *
 * <p>Fixture ({@code schema.graphqls}): {@code Category.parentRef} (single) and
 * {@code Category.childRefs} (list) are both multi-table polymorphic child fields returning
 * {@code CategoryRef} (interface implemented by {@code CategoryNode}, backed by the same
 * {@code category} table reached via the self FK stated with {@code @referenceFor}, and
 * {@code CategoryLabel}, backed by {@code category_label} with an auto-discovered single FK, which
 * keeps the field multi-table). Both fields state the <em>same</em> self-FK key; the orientation is
 * therefore decided solely by cardinality.
 *
 * <p>Seeded category hierarchy (init.sql): Genre(1) → Action(2), Animation(3), Comedy(4);
 * Action(2) → Thriller(5). {@code category_label} rows exist for categories 1 and 4 only, so
 * querying Action(2) isolates the self-FK ({@code CategoryNode}) branch. The single-valued
 * {@code parentRef} must navigate <em>to</em> Action's parent (Genre); the list {@code childRefs}
 * must collect Action's <em>children</em> (Thriller). Opposite directions from one key.
 */
@ExecutionTier
class MultiTablePolymorphicSelfFkOrientationExecutionTest {

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
    private Map<String, Object> categoryById(int id, String selection) {
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [" + id + "]) { categoryId name " + selection + " } }");
        var list = (List<Map<String, Object>>) data.get("categoryById");
        assertThat(list).as("categoryById(%s) returns exactly one row", id).hasSize(1);
        return list.get(0);
    }

    private static final String REF_SELECTION =
        "{ __typename ... on CategoryNode { rowId name } ... on CategoryLabel { rowId label } }";

    @SuppressWarnings("unchecked")
    @Test
    void singleCardinalitySelfFk_navigatesToParentCategory() {
        // parentRef is single-cardinality: the SAME self-FK key orients toward the parent, putting the
        // correlation column on the parent side (parent.parent_category_id, a non-key parent column).
        // R481's ParentRowDemand capability force-projects that column onto the parent SELECT, so the
        // single-fetch WHERE reads it. Action(2)'s parent is Genre(1); CategoryLabel is empty for
        // category 2 (no category_label row), so the single result is deterministically the Genre node.
        var action = categoryById(2, "parentRef " + REF_SELECTION);
        var parentRef = (Map<String, Object>) action.get("parentRef");
        assertThat(parentRef)
            .as("Action's single parentRef navigates to its parent category via the self-FK")
            .containsEntry("__typename", "CategoryNode")
            .containsEntry("rowId", 1)
            .containsEntry("name", "Genre");
    }

    @SuppressWarnings("unchecked")
    @Test
    void listCardinalitySelfFk_collectsChildCategories() {
        // childRefs is a list: the SAME self-FK key orients the OPPOSITE way, collecting the
        // categories that point back at Action. Querying Action(2) isolates the self-FK branch
        // (no category_label row has category_id 2), so the only child is Thriller(5). A flipped
        // orientation would instead yield Action's single parent (Genre) — the bug this guards.
        var action = categoryById(2, "childRefs " + REF_SELECTION);
        var childRefs = (List<Map<String, Object>>) action.get("childRefs");
        assertThat(childRefs)
            .as("Action's list childRefs collects its child categories via the reversed self-FK")
            .hasSize(1);
        assertThat(childRefs.get(0))
            .containsEntry("__typename", "CategoryNode")
            .containsEntry("rowId", 5)
            .containsEntry("name", "Thriller");
    }

    @SuppressWarnings("unchecked")
    @Test
    void listCardinality_multiTableDispatch_bothParticipantsContribute() {
        // Genre(1) exercises both branches at once: the self-FK CategoryNode branch collects its
        // three child categories (Action, Animation, Comedy), and the auto-discovered CategoryLabel
        // branch contributes the genre-label row (category_label.category_id = 1). This proves the
        // field stays a genuine multi-table dispatch, not a self-FK-only special case.
        var genre = categoryById(1, "childRefs " + REF_SELECTION);
        var childRefs = (List<Map<String, Object>>) genre.get("childRefs");

        var nodeNames = childRefs.stream()
            .filter(r -> "CategoryNode".equals(r.get("__typename")))
            .map(r -> (String) r.get("name")).toList();
        var labels = childRefs.stream()
            .filter(r -> "CategoryLabel".equals(r.get("__typename")))
            .map(r -> (String) r.get("label")).toList();

        assertThat(nodeNames)
            .as("Genre's child categories via the reversed self-FK (CategoryNode branch)")
            .containsExactlyInAnyOrder("Action", "Animation", "Comedy");
        assertThat(labels)
            .as("Genre's category_label via the auto-discovered FK (CategoryLabel branch)")
            .containsExactly("genre-label");
    }
}
