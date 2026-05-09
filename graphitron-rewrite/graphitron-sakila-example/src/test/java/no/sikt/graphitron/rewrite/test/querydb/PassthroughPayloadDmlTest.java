package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R75 Phase 1 execution-tier coverage for passthrough payload mutations against PostgreSQL.
 * Each verb's {@code *Passthrough} mutation returns {@code FilmPassthroughPayload}, a plain
 * SDL Object wrapping a single {@code @table}-element data field — no Java carrier on disk.
 * The classifier resolves the SDL return type through {@code unwrapPassthroughPayload} into
 * the data field's {@code TableBoundReturnType}; the per-kind DML emitter projects onto
 * {@code film}; graphql-java's identity-passthrough fetcher
 * ({@code env -> env.getSource()}) hands the resulting {@code Result<Record>} back to
 * graphql-java's traversal, which walks through {@code Film}'s per-field fetchers.
 *
 * <p>The matrix verifies that the per-kind {@code RETURNING} projection emits compile-correct
 * DSL (compile tier catches mismatches via {@code mvn compile -pl :graphitron-sakila-example
 * -Plocal-db}) and runs end-to-end against PostgreSQL.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class PassthroughPayloadDmlTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var context = new GraphitronContext() {
            @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
            @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
        };
        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put(GraphitronContext.class, context))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private Integer insertFilm(String title) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private void deleteFilmById(int filmId) {
        dsl.deleteFrom(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId))
            .execute();
    }

    private String randomMarker(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    @Test
    void createFilmsPassthrough_writesRowsAndReturnsPayloadWithDataField() {
        String m1 = randomMarker("R75-INSERT-A");
        String m2 = randomMarker("R75-INSERT-B");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmsPassthrough(in: [
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 }
                    ]) {
                        films { title languageId }
                    }
                }
                """.formatted(m1, m2));
            // graphql-java traverses FilmPassthroughPayload through the IdentityPassthrough
            // fetcher; the films field arrives as a list of Film records. No carrier class on
            // disk — the wire-format wrap is resolved entirely at the boundary.
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmsPassthrough");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("films");
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("title")).containsExactlyInAnyOrder(m1, m2);
        } finally {
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("title").in(m1, m2)).execute();
        }
    }

    @Test
    void updateFilmsPassthrough_updatesRowsAndReturnsPayloadWithDataField() {
        String m1 = randomMarker("R75-UPDATE-A");
        Integer id1 = insertFilm(m1);
        String new1 = randomMarker("R75-UPDATED-A");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilmsPassthrough(in: [
                        { filmId: %d, title: "%s" }
                    ]) {
                        films { filmId title }
                    }
                }
                """.formatted(id1, new1));
            Map<String, Object> payload = (Map<String, Object>) data.get("updateFilmsPassthrough");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("films");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("title")).isEqualTo(new1);

            String dbTitle = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id1))
                .fetchOne().value1();
            assertThat(dbTitle).isEqualTo(new1);
        } finally {
            deleteFilmById(id1);
        }
    }

    @Test
    void deleteFilmsPassthrough_capturesPreDeleteRowAndReturnsPayloadWithDataField() {
        String m1 = randomMarker("R75-DELETE-A");
        Integer id1 = insertFilm(m1);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteFilmsPassthrough(in: [{ filmId: %d }]) {
                        films { filmId title }
                    }
                }
                """.formatted(id1));
            Map<String, Object> payload = (Map<String, Object>) data.get("deleteFilmsPassthrough");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("films");
            // RETURNING captures pre-delete state — the deleted row appears in the payload.
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("title")).isEqualTo(m1);

            // Row is gone from the table.
            int countAfter = dsl.fetchCount(DSL.table("film"),
                DSL.field("film_id", Integer.class).eq(id1));
            assertThat(countAfter).isZero();
        } finally {
            deleteFilmById(id1);
        }
    }

    @Test
    void upsertFilmsPassthrough_writesRowsAndReturnsPayloadAcrossBothBranches() {
        int novelId = 999_201;
        deleteFilmById(novelId);
        String existingMarker = randomMarker("R75-UPSERT-EXISTING");
        Integer existingId = insertFilm(existingMarker);
        String upsertedExisting = randomMarker("R75-UPSERT-UPDATED");
        String novelMarker = randomMarker("R75-UPSERT-NOVEL");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilmsPassthrough(in: [
                        { filmId: %d, title: "%s", languageId: 1 },
                        { filmId: %d, title: "%s", languageId: 1 }
                    ]) {
                        films { filmId title }
                    }
                }
                """.formatted(existingId, upsertedExisting, novelId, novelMarker));
            Map<String, Object> payload = (Map<String, Object>) data.get("upsertFilmsPassthrough");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("films");
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("title"))
                .containsExactlyInAnyOrder(upsertedExisting, novelMarker);

            String dbTitleExisting = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(existingId))
                .fetchOne().value1();
            assertThat(dbTitleExisting).isEqualTo(upsertedExisting);
        } finally {
            deleteFilmById(existingId);
            deleteFilmById(novelId);
        }
    }
}
