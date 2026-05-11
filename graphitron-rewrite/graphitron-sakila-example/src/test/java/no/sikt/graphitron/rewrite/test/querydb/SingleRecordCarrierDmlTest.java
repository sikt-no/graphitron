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
                        film { filmId title languageId }
                    }
                }
                """.formatted(m));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(m);
            assertThat(row.get("languageId")).isEqualTo(1);
            // filmId is auto-generated; the input didn't carry it. A non-null filmId on the
            // response proves the follow-up SELECT projected the DB column, not echoed input.
            assertThat(row.get("filmId"))
                .as("filmId comes from the follow-up SELECT, not from the input map")
                .isNotNull();
            assertThat(((Number) row.get("filmId")).intValue()).isPositive();
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(m)).execute();
        }
    }

    /**
     * Strong proof that the follow-up SELECT runs and honors the selection set: query
     * {@code rentalDuration} (DB default {@code 3}) without setting it in the input, and
     * verify it comes back populated. An echo-the-input emit would return {@code null} (the
     * input map has no key for {@code rentalDuration}); the two-step SELECT reads the DB
     * after the DML committed and surfaces the default.
     */
    @Test
    void createFilmPayload_followupSelect_returnsColumnDefault() {
        String m = randomMarker("R75-DEFAULT");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmPayload(in: { title: "%s", languageId: 1 }) {
                        film { rentalDuration }
                    }
                }
                """.formatted(m));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("rentalDuration"))
                .as("rentalDuration comes from the follow-up SELECT projection (DB DEFAULT 3), not the input")
                .isNotNull();
            assertThat(((Number) row.get("rentalDuration")).intValue()).isEqualTo(3);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(m)).execute();
        }
    }

    /**
     * The follow-up SELECT's {@code $fields(env.getSelectionSet(), <table>, env)} projection
     * must include any {@code @reference} the selection set requests. Querying
     * {@code languageName} (a {@code @reference} from {@code Film.languageName} to
     * {@code Language.name} via the {@code film_language_id_fkey} FK) exercises the
     * correlated-subquery path on the response SELECT.
     */
    @Test
    void createFilmPayload_followupSelect_projectsReferenceField() {
        String m = randomMarker("R75-REF");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmPayload(in: { title: "%s", languageId: 1 }) {
                        film { title languageName }
                    }
                }
                """.formatted(m));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(m);
            assertThat(((String) row.get("languageName")).strip())
                .as("@reference projection on the carrier's data field returns Language.name")
                .isEqualTo("English");
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
                        film { filmId title languageId rentalDuration }
                    }
                }
                """.formatted(id, updated));
            Map<String, Object> payload = (Map<String, Object>) data.get("updateFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            // title was changed in the UPDATE.
            assertThat(row.get("title")).isEqualTo(updated);
            // filmId was the lookupKey; it should match the original.
            assertThat(((Number) row.get("filmId")).intValue()).isEqualTo(id);
            // languageId / rentalDuration were not in the input map. The follow-up SELECT
            // reads the DB after UPDATE; an echo-the-input emit would return null for both.
            assertThat(row.get("languageId"))
                .as("languageId comes from the follow-up SELECT, not the input")
                .isNotNull();
            assertThat(row.get("rentalDuration"))
                .as("rentalDuration (DB default 3) comes from the follow-up SELECT")
                .isNotNull();

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
                        film { filmId title rentalDuration languageName }
                    }
                }
                """.formatted(novelId, novelMarker));
            Map<String, Object> payload = (Map<String, Object>) data.get("upsertFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(novelMarker);
            assertThat(((Number) row.get("filmId")).intValue()).isEqualTo(novelId);
            // Insert branch took the column DEFAULT for rentalDuration; the SELECT reads it.
            assertThat(((Number) row.get("rentalDuration")).intValue()).isEqualTo(3);
            // @reference projection works through the carrier's data field on UPSERT too.
            assertThat(((String) row.get("languageName")).strip()).isEqualTo("English");
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
                        film { filmId title rentalDuration }
                    }
                }
                """.formatted(existingId, upsertedMarker));
            Map<String, Object> payload = (Map<String, Object>) data.get("upsertFilmPayload");
            Map<String, Object> row = (Map<String, Object>) payload.get("film");
            assertThat(row.get("title")).isEqualTo(upsertedMarker);
            assertThat(((Number) row.get("filmId")).intValue()).isEqualTo(existingId);
            // rentalDuration was set to the DB default (3) by insertFilm; the input didn't
            // change it. The SELECT must surface that value — proving the response read the DB
            // after the UPSERT update branch, not echoed input.
            assertThat(((Number) row.get("rentalDuration")).intValue()).isEqualTo(3);

            String dbTitle = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(existingId))
                .fetchOne().value1();
            assertThat(dbTitle).isEqualTo(upsertedMarker);
        } finally {
            deleteFilmById(existingId);
        }
    }
}
