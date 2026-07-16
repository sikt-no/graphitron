package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for nested non-{@code @table} grouping inputs that flatten onto the
 * outer {@code film} table, exercised against PostgreSQL across every classifying verb plus both
 * UPDATE walker paths (direct-{@code @table} return single + bulk-VALUES-join, payload return). The
 * load-bearing proof is the absent-vs-null contract observable on the wire: the access-path walk in
 * the generated Java must descend the nested wire map and, at every layer, distinguish an absent key
 * (skip / DEFAULT) from a present {@code null} (write {@code NULL}) from a present value.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class NestedInputDmlExecutionTest {

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

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    /**
     * Run a mutation that must fail at a runtime guard. The guard throws an
     * {@link IllegalArgumentException} which {@code ErrorRouter.redact} maps to a correlation-id
     * error on the wire (the internal message is deliberately not exposed), so the observable
     * contract is "the operation errored and wrote nothing" — asserted here as a non-empty error
     * list; callers additionally assert the target row is unchanged.
     */
    private void executeExpectingError(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("expected the runtime guard to surface a graphql error").isNotEmpty();
    }

    private int insertFilm(String title, String description) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("description"), description)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private String dbTitle(int filmId) {
        return dsl.select(DSL.field("title", String.class)).from(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId)).fetchOne().value1();
    }

    private String dbDescription(int filmId) {
        return dsl.select(DSL.field("description", String.class)).from(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId)).fetchOne().value1();
    }

    private void deleteFilm(int filmId) {
        dsl.deleteFrom(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(filmId)).execute();
    }

    // ---- INSERT ---------------------------------------------------------------------------------

    @Test
    void insertNested_absentLeafLandsColumnDefault_presentLeafBindsValue() {
        String m1 = "R186-INS-" + UUID.randomUUID();
        String m2 = "R186-INS-" + UUID.randomUUID();
        try {
            // rentalDuration absent under the nested group → DEFAULT (sakila: smallint NOT NULL DEFAULT 3).
            execute("""
                mutation { createFilmNested(in: { languageId: 1, details: { title: "%s" } }) { filmId } }
                """.formatted(m1));
            var row1 = dsl.select(DSL.field("rental_duration", Integer.class)).from(DSL.table("film"))
                .where(DSL.field("title").eq(m1)).fetchOne();
            assertThat(row1.value1()).as("absent nested leaf → column DEFAULT").isEqualTo(3);

            // rentalDuration present under the nested group → bound value.
            execute("""
                mutation { createFilmNested(in: { languageId: 1, details: { title: "%s", rentalDuration: 7 } }) { filmId } }
                """.formatted(m2));
            var row2 = dsl.select(DSL.field("rental_duration", Integer.class)).from(DSL.table("film"))
                .where(DSL.field("title").eq(m2)).fetchOne();
            assertThat(row2.value1()).as("present nested leaf → bound value").isEqualTo(7);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").in(m1, m2)).execute();
        }
    }

    // ---- UPDATE (direct-@table return, single) --------------------------------------------------

    @Test
    void updateNested_honorsAbsentVsNullVsValuePerLeaf() {
        int id = insertFilm("R186-orig-" + UUID.randomUUID(), "orig-desc");
        try {
            // Leaf present with value + leaf present with null: title written, description cleared to NULL.
            execute("""
                mutation { updateFilmNested(in: { filmId: %d, details: { title: "renamed", description: null } }) { filmId } }
                """.formatted(id));
            assertThat(dbTitle(id)).isEqualTo("renamed");
            assertThat(dbDescription(id)).as("present-null leaf writes SQL NULL").isNull();

            // Restore description, then send a present group with the description leaf ABSENT:
            // title written, description untouched (PATCH).
            dsl.update(DSL.table("film")).set(DSL.field("description"), "restored")
                .where(DSL.field("film_id", Integer.class).eq(id)).execute();
            execute("""
                mutation { updateFilmNested(in: { filmId: %d, details: { title: "renamed2" } }) { filmId } }
                """.formatted(id));
            assertThat(dbTitle(id)).isEqualTo("renamed2");
            assertThat(dbDescription(id)).as("absent leaf leaves the column untouched").isEqualTo("restored");
        } finally {
            deleteFilm(id);
        }
    }

    @Test
    void updateNested_absentOuterGroup_skipsWholeGroup_emptySetGuardFires() {
        int id = insertFilm("R186-empty-" + UUID.randomUUID(), "desc");
        try {
            // Outer key absent → no claim about the group → no settable fields → runtime empty-SET guard.
            executeExpectingError("""
                mutation { updateFilmNested(in: { filmId: %d }) { filmId } }
                """.formatted(id));
            assertThat(dbDescription(id)).as("a guarded no-op writes nothing").isEqualTo("desc");
        } finally {
            deleteFilm(id);
        }
    }

    @Test
    void updateNested_nullOuterGroup_skipsWholeGroup_emptySetGuardFires() {
        int id = insertFilm("R186-null-" + UUID.randomUUID(), "desc");
        try {
            // Outer key present with null value → no claim about the group (reading 1) → empty-SET guard.
            executeExpectingError("""
                mutation { updateFilmNested(in: { filmId: %d, details: null }) { filmId } }
                """.formatted(id));
            assertThat(dbDescription(id)).as("a present-null outer group writes nothing").isEqualTo("desc");
        } finally {
            deleteFilm(id);
        }
    }

    // ---- UPDATE (payload return, single) --------------------------------------------------------

    @Test
    void updateNestedPayload_writesNestedLeavesAndReturnsPayload() {
        int id = insertFilm("R186-pl-" + UUID.randomUUID(), "old");
        try {
            Map<String, Object> data = execute("""
                mutation { updateFilmNestedPayload(in: { filmId: %d, details: { title: "plnew", description: "pldesc" } }) {
                    film { filmId title }
                } }
                """.formatted(id));
            var payload = (Map<String, Object>) data.get("updateFilmNestedPayload");
            var film = (Map<String, Object>) payload.get("film");
            assertThat(film.get("title")).isEqualTo("plnew");
            assertThat(dbDescription(id)).isEqualTo("pldesc");
        } finally {
            deleteFilm(id);
        }
    }

    // ---- UPDATE (direct-@table return, bulk VALUES-join) ----------------------------------------

    @Test
    void updateFilmsNested_bulkValuesJoin_updatesEveryRow() {
        int a = insertFilm("R186-bulk-a-" + UUID.randomUUID(), "da");
        int b = insertFilm("R186-bulk-b-" + UUID.randomUUID(), "db");
        try {
            execute("""
                mutation { updateFilmsNested(in: [
                    { filmId: %d, details: { title: "bulkA" } },
                    { filmId: %d, details: { title: "bulkB" } }
                ]) { filmId } }
                """.formatted(a, b));
            assertThat(dbTitle(a)).isEqualTo("bulkA");
            assertThat(dbTitle(b)).isEqualTo("bulkB");
        } finally {
            deleteFilm(a);
            deleteFilm(b);
        }
    }

    @Test
    void updateFilmsNested_divergentNestedShape_tripsUniformShapeGuard() {
        int a = insertFilm("R186-div-a-" + UUID.randomUUID(), "da");
        int b = insertFilm("R186-div-b-" + UUID.randomUUID(), "db");
        try {
            // Row 0 presents the `title` leaf; row 1 presents `description`. The per-nesting-node
            // uniform-shape guard rejects the divergence (one VALUES column list cannot fit both).
            executeExpectingError("""
                mutation { updateFilmsNested(in: [
                    { filmId: %d, details: { title: "x" } },
                    { filmId: %d, details: { description: "y" } }
                ]) { filmId } }
                """.formatted(a, b));
            assertThat(dbTitle(a)).as("the guarded bulk statement wrote nothing").startsWith("R186-div-a-");
        } finally {
            deleteFilm(a);
            deleteFilm(b);
        }
    }

    // ---- DELETE ---------------------------------------------------------------------------------

    @Test
    void deleteNested_nestedKeyGroupIdentifiesAndDeletesRow() {
        int id = insertFilm("R186-del-" + UUID.randomUUID(), "desc");
        boolean deleted = false;
        try {
            execute("""
                mutation { deleteFilmNested(in: { keys: { filmId: %d } }) }
                """.formatted(id));
            var remaining = dsl.selectCount().from(DSL.table("film"))
                .where(DSL.field("film_id", Integer.class).eq(id)).fetchOne().value1();
            assertThat(remaining).as("nested-key DELETE removed the row").isZero();
            deleted = true;
        } finally {
            if (!deleted) deleteFilm(id);
        }
    }
}
