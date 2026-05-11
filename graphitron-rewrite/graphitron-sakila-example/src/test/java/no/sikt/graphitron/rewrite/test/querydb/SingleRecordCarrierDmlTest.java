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
        return executeRaw(query, /*expectErrors=*/ false).data;
    }

    private RawResult executeRaw(String query, boolean expectErrors) {
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
        if (!expectErrors) {
            assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        }
        return new RawResult(result.getData(), result.getErrors());
    }

    private record RawResult(Map<String, Object> data, java.util.List<graphql.GraphQLError> errors) {}

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

    /**
     * R75 Phase 1 headline durability pin: when the response SELECT (or any nested traversal
     * on the carrier path) throws, the row must still be in the DB. The DML's
     * {@code transactionResult(...)} commits when the mutation fetcher returns; the field-level
     * fetcher for {@code durabilityError} on {@code Film} throws via
     * {@code DurabilityErrorService.synthesize}, which graphql-java reports as a field error on
     * the response — but the row's existence is independent of that traversal.
     */
    @Test
    void dml_persists_when_followupSelect_throws() {
        String m = randomMarker("R75-DURABILITY-CARRIER");
        try {
            var raw = executeRaw("""
                mutation {
                    createFilmPayload(in: { title: "%s", languageId: 1 }) {
                        film { filmId title durabilityError }
                    }
                }
                """.formatted(m), /*expectErrors=*/ true);
            // The field error from durabilityError surfaces on the response. Per the
            // redaction contract, the error message is a UUID-keyed reference; the field
            // path is what makes it diagnosable. Assert by path.
            assertThat(raw.errors())
                .as("expected at least one field error from durabilityError")
                .isNotEmpty();
            assertThat(raw.errors())
                .extracting(e -> e.getPath() == null ? "" : e.getPath().toString())
                .anyMatch(p -> p.contains("durabilityError"));
            // The DML committed: the row exists in the DB after the response.
            int count = dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(m));
            assertThat(count).as("row must persist despite the mid-traversal field error").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(m)).execute();
        }
    }

    /**
     * R75 Phase 1 durability pin replayed on the direct-{@code @table}-return path. The
     * {@code createFilm: Film} (etc.) emit changed from single-statement
     * {@code INSERT ... RETURNING $fields(...)} to the two-step shape (PK-only RETURNING inside
     * {@code transactionResult}, follow-up SELECT outside it); the same durability invariant
     * must hold here. Field error during traversal of the Film record's children must not undo
     * the DML.
     */
    @Test
    void dml_persists_when_directReturnSelect_throws() {
        String m = randomMarker("R75-DURABILITY-DIRECT");
        try {
            var raw = executeRaw("""
                mutation {
                    createFilm(in: { title: "%s", languageId: 1 }) {
                        filmId title durabilityError
                    }
                }
                """.formatted(m), /*expectErrors=*/ true);
            assertThat(raw.errors())
                .as("expected at least one field error from durabilityError")
                .isNotEmpty();
            assertThat(raw.errors())
                .extracting(e -> e.getPath() == null ? "" : e.getPath().toString())
                .anyMatch(p -> p.contains("durabilityError"));
            int count = dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(m));
            assertThat(count).as("row must persist on the direct-@table path despite mid-traversal field error").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(m)).execute();
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
