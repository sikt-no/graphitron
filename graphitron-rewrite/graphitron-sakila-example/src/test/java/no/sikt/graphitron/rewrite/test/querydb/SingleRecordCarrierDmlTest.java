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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R75 Phase 1 execution-tier coverage for single-record DML carrier mutations against
 * PostgreSQL. Each verb's {@code *Payload} mutation returns {@code FilmPayload}, a plain
 * SDL Object wrapping a single {@code @table}-element data field — no Java carrier on
 * disk. The classifier promotes {@code FilmPayload} to {@code PojoResultType.NoBacking};
 * the mutation classifies as {@code MutationField.MutationDmlRecordField} with PK-only
 * RETURNING inside a tight transaction; the data field classifies as
 * {@code ChildField.SingleRecordTableField} and runs the response SELECT outside the
 * transaction. Read errors during traversal propagate as graphql-java field errors and
 * cannot undo the DML.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SingleRecordCarrierDmlTest {

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
    void createFilmPayload_writesRowAndReturnsPayloadWithSingleDataField() {
        String m = randomMarker("R75-INSERT");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmPayload(in: { title: "%s", languageId: 1 }) {
                        film { title languageId }
                    }
                }
                """.formatted(m));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(m);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(m)).execute();
        }
    }

    @Test
    void updateFilmPayload_updatesRowAndReturnsPayloadWithSingleDataField() {
        String original = randomMarker("R75-UPDATE-ORIG");
        Integer id = insertFilm(original);
        String updated = randomMarker("R75-UPDATE-NEW");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilmPayload(in: { filmId: %d, title: "%s" }) {
                        film { filmId title }
                    }
                }
                """.formatted(id, updated));
            Map<String, Object> payload = (Map<String, Object>) data.get("updateFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(updated);

            String dbTitle = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id))
                .fetchOne().value1();
            assertThat(dbTitle).isEqualTo(updated);
        } finally {
            deleteFilmById(id);
        }
    }

    @Test
    void upsertFilmPayload_insertsNewRowAndReturnsPayload() {
        int novelId = 999_301;
        deleteFilmById(novelId);
        String novelMarker = randomMarker("R75-UPSERT-NOVEL");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilmPayload(in: { filmId: %d, title: "%s", languageId: 1 }) {
                        film { filmId title }
                    }
                }
                """.formatted(novelId, novelMarker));
            Map<String, Object> payload = (Map<String, Object>) data.get("upsertFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(novelMarker);
        } finally {
            deleteFilmById(novelId);
        }
    }

    @Test
    void upsertFilmPayload_updatesExistingRowAndReturnsPayload() {
        String existingMarker = randomMarker("R75-UPSERT-EXISTING");
        Integer existingId = insertFilm(existingMarker);
        String upsertedMarker = randomMarker("R75-UPSERT-UPDATED");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilmPayload(in: { filmId: %d, title: "%s", languageId: 1 }) {
                        film { filmId title }
                    }
                }
                """.formatted(existingId, upsertedMarker));
            Map<String, Object> payload = (Map<String, Object>) data.get("upsertFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(upsertedMarker);

            String dbTitle = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(existingId))
                .fetchOne().value1();
            assertThat(dbTitle).isEqualTo(upsertedMarker);
        } finally {
            deleteFilmById(existingId);
        }
    }
}
