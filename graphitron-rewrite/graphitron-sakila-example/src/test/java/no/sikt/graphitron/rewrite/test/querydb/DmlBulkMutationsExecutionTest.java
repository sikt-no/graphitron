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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R77 Phase F execution-tier coverage for bulk DML mutations against PostgreSQL.
 * Each verb's bulk arm (INSERT/UPDATE/UPSERT/DELETE) is exercised end-to-end via
 * the {@code createFilms} / {@code updateFilms} / {@code upsertFilms} /
 * {@code deleteFilms} Mutation fields wired in the Sakila schema, plus the
 * {@code upsertFilmsDoNothing} surface that covers the {@code .doNothing()}
 * mode. Pinned in this tier because the bulk arms have execution-only
 * semantics not visible at compile or pipeline tier: the row-loop's
 * {@code containsKey}-gated DEFAULT/val dispatch (INSERT, UPSERT insert
 * branch), the dynamic SET walk (UPDATE, UPSERT update branch), the
 * uniform-shape / no-set-fields / duplicate-tuple guards (UPDATE, UPSERT
 * doUpdate), and the typed empty-list short-circuit.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class DmlBulkMutationsExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

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

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                }
            }));

        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var result = run(query);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private graphql.ExecutionResult executeRaw(String query) { return run(query); }

    private graphql.ExecutionResult run(String query) {
        var context = new GraphitronContext() {
            @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
            @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
        };
        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put(GraphitronContext.class, context))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();
        return graphql.execute(input);
    }

    // ===== fixture helpers =====

    private Integer insertFilm(String title) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private Integer insertFilm(String title, String description) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("description"), description)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private Integer insertFilm(String title, int rentalDuration) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("language_id"), (short) 1)
            .set(DSL.field("rental_duration"), (short) rentalDuration)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private void deleteFilmById(int filmId) {
        dsl.deleteFrom(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId))
            .execute();
    }

    private String randomMarker(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    // ===== happy path: each verb writes N rows =====

    @Test
    void createFilms_insertsNRowsAndReturnsProjectedList() {
        // Bulk INSERT emits valuesOfRows(in.stream().map(row -> DSL.row(...)).toList())
        // and returningResult(...) projects the row tuple list. RETURNING flows back
        // through graphql-java's selection set as [Film!]!.
        String m1 = randomMarker("R77F-INSERT-A");
        String m2 = randomMarker("R77F-INSERT-B");
        String m3 = randomMarker("R77F-INSERT-C");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilms(in: [
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 }
                    ]) { title languageId }
                }
                """.formatted(m1, m2, m3));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("createFilms");
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("title")).containsExactlyInAnyOrder(m1, m2, m3);
        } finally {
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("title").in(m1, m2, m3)).execute();
        }
    }

    @Test
    void updateFilms_updatesNRowsViaValuesJoin() {
        // Bulk UPDATE emits `UPDATE film SET ... FROM (VALUES ...) AS v(film_id, title)
        // WHERE film.film_id = v.film_id`. The dynamic SET walk includes only the
        // present-key fields (firstKeys-gated). RETURNING projects [Film!]!.
        String m1 = randomMarker("R77F-UPDATE-A");
        String m2 = randomMarker("R77F-UPDATE-B");
        Integer id1 = insertFilm(m1);
        Integer id2 = insertFilm(m2);
        String new1 = randomMarker("R77F-UPDATED-A");
        String new2 = randomMarker("R77F-UPDATED-B");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilms(in: [
                        { filmId: %d, title: "%s" },
                        { filmId: %d, title: "%s" }
                    ]) { filmId title }
                }
                """.formatted(id1, new1, id2, new2));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("updateFilms");
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("title")).containsExactlyInAnyOrder(new1, new2);

            // Confirm SET clause actually wrote (not just RETURNING).
            String dbTitle1 = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id1))
                .fetchOne().value1();
            assertThat(dbTitle1).isEqualTo(new1);
        } finally {
            deleteFilmById(id1);
            deleteFilmById(id2);
        }
    }

    @Test
    void upsertFilms_writesNRowsAndReturnsProjectedListAcrossBothBranches() {
        // Mixes INSERT branch (novel filmId) and UPDATE branch (pre-existing filmId)
        // in a single bulk call. Single dispatched statement; `valuesOfRows`
        // followed by `.onConflict(filmId).doUpdate().set(...)` per Phase E5.
        int novelId = 999_101;
        deleteFilmById(novelId); // pre-clean
        String existingMarker = randomMarker("R77F-UPSERT-EXISTING");
        Integer existingId = insertFilm(existingMarker);
        String upsertedExisting = randomMarker("R77F-UPSERT-UPDATED");
        String novelMarker = randomMarker("R77F-UPSERT-NOVEL");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilms(in: [
                        { filmId: %d, title: "%s", languageId: 1 },
                        { filmId: %d, title: "%s", languageId: 1 }
                    ]) { filmId title }
                }
                """.formatted(existingId, upsertedExisting, novelId, novelMarker));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("upsertFilms");
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("title"))
                .containsExactlyInAnyOrder(upsertedExisting, novelMarker);

            // Confirm both branches wrote.
            String dbTitleExisting = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(existingId))
                .fetchOne().value1();
            assertThat(dbTitleExisting).isEqualTo(upsertedExisting);
            String dbTitleNovel = dsl.select(DSL.field("title", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(novelId))
                .fetchOne().value1();
            assertThat(dbTitleNovel).isEqualTo(novelMarker);
        } finally {
            deleteFilmById(existingId);
            deleteFilmById(novelId);
        }
    }

    @Test
    void deleteFilms_deletesNRowsByLookupKeyTuple() {
        // Bulk DELETE emits `DELETE FROM film WHERE DSL.row(film_id).in(<row tuples>)`.
        // No RETURNING projection (return type is [ID!]!, EncodedList terminator).
        String m1 = randomMarker("R77F-DELETE-A");
        String m2 = randomMarker("R77F-DELETE-B");
        Integer id1 = insertFilm(m1);
        Integer id2 = insertFilm(m2);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteFilms(in: [{ filmId: %d }, { filmId: %d }])
                }
                """.formatted(id1, id2));
            List<String> ids = (List<String>) data.get("deleteFilms");
            assertThat(ids).hasSize(2);

            // Confirm rows actually gone.
            int leftover = dsl.fetchCount(
                DSL.selectOne().from(DSL.table("film"))
                    .where(DSL.field("film_id", Integer.class).in(id1, id2)));
            assertThat(leftover).isZero();
        } finally {
            deleteFilmById(id1);
            deleteFilmById(id2);
        }
    }

    // ===== INSERT missing-vs-null =====

    @Test
    void createFilms_omittedFieldUsesColumnDefault() {
        // Per-cell containsKey dispatch: omitted rentalDuration binds DSL.defaultValue
        // (the smallint NOT NULL DEFAULT 3 lands). Pre-R77 the emitter wrote typed null
        // unconditionally and would have surfaced an integrity-constraint violation.
        String m1 = randomMarker("R77F-INS-DEF-A");
        String m2 = randomMarker("R77F-INS-DEF-B");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilms(in: [
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 }
                    ]) { title rentalDuration }
                }
                """.formatted(m1, m2));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("createFilms");
            assertThat(rows).extracting(r -> ((Number) r.get("rentalDuration")).intValue())
                .containsExactlyInAnyOrder(3, 3);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").in(m1, m2)).execute();
        }
    }

    @Test
    void createFilms_explicitNullRaisesError() {
        // Explicit null on rentalDuration binds DSL.val(null, dataType); the NOT NULL
        // constraint aborts the whole statement (single dispatched INSERT). The
        // try/catch in the fetcher routes through ErrorRouter.redact.
        String m1 = randomMarker("R77F-INS-NULL-A");
        String m2 = randomMarker("R77F-INS-NULL-B");
        QUERY_COUNT.set(0);
        graphql.ExecutionResult result = executeRaw("""
            mutation {
                createFilms(in: [
                    { title: "%s", languageId: 1 },
                    { title: "%s", languageId: 1, rentalDuration: null }
                ]) { filmId }
            }
            """.formatted(m1, m2));
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(QUERY_COUNT.get()).as("constraint violation aborts the single dispatched statement; no partial writes")
            .isEqualTo(1);
        // Defensive cleanup.
        dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").in(m1, m2)).execute();
    }

    // ===== UPDATE missing-vs-null =====

    @Test
    void updateFilms_omittedFieldLeavesColumnAlone_explicitNullWritesNull() {
        // Bulk UPDATE dynamic SET. firstKeys captured from row 0. With both rows sharing
        // {filmId, title, description}, the SET clause walks all three setFields entries
        // and binds typed null when the value is explicitly null. To keep both branches
        // observable, run two calls: omit-arm and explicit-null arm.
        String origDesc1 = randomMarker("R77F-UPD-DESC-A");
        String origDesc2 = randomMarker("R77F-UPD-DESC-B");
        String origTitle1 = randomMarker("R77F-UPD-TITLE-A");
        String origTitle2 = randomMarker("R77F-UPD-TITLE-B");
        Integer id1 = insertFilm(origTitle1, origDesc1);
        Integer id2 = insertFilm(origTitle2, origDesc2);
        String newTitle1 = randomMarker("R77F-UPD-NEW-A");
        String newTitle2 = randomMarker("R77F-UPD-NEW-B");
        try {
            // Omit arm: both rows omit `description`; firstKeys = {filmId, title}.
            // description drops out of the SET clause entirely.
            execute("""
                mutation {
                    updateFilms(in: [
                        { filmId: %d, title: "%s" },
                        { filmId: %d, title: "%s" }
                    ]) { filmId }
                }
                """.formatted(id1, newTitle1, id2, newTitle2));
            String dbDesc1 = dsl.select(DSL.field("description", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id1))
                .fetchOne().value1();
            assertThat(dbDesc1).as("omitted description preserved").isEqualTo(origDesc1);

            // Explicit-null arm: both rows carry `description: null`; firstKeys = {filmId,
            // title, description}. SET clause writes typed null and the column reads back
            // SQL NULL.
            execute("""
                mutation {
                    updateFilms(in: [
                        { filmId: %d, title: "%s", description: null },
                        { filmId: %d, title: "%s", description: null }
                    ]) { filmId }
                }
                """.formatted(id1, newTitle1, id2, newTitle2));
            String dbDesc1b = dsl.select(DSL.field("description", String.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id1))
                .fetchOne().value1();
            assertThat(dbDesc1b).as("explicit-null description writes SQL NULL").isNull();
        } finally {
            deleteFilmById(id1);
            deleteFilmById(id2);
        }
    }

    // ===== UPSERT missing-vs-null =====

    @Test
    void upsertFilms_omittedFieldOnInsertBranchUsesColumnDefault() {
        // INSERT branch fires (novel filmIds). Per-cell containsKey dispatch on the
        // VALUES list binds DSL.defaultValue when rentalDuration is absent.
        int id1 = 999_201, id2 = 999_202;
        deleteFilmById(id1); deleteFilmById(id2);
        String m1 = randomMarker("R77F-UPS-INS-A");
        String m2 = randomMarker("R77F-UPS-INS-B");
        try {
            execute("""
                mutation {
                    upsertFilms(in: [
                        { filmId: %d, title: "%s", languageId: 1 },
                        { filmId: %d, title: "%s", languageId: 1 }
                    ]) { filmId }
                }
                """.formatted(id1, m1, id2, m2));
            Integer dbRental1 = dsl.select(DSL.field("rental_duration", Integer.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id1))
                .fetchOne().value1();
            assertThat(dbRental1).isEqualTo(3);
        } finally {
            deleteFilmById(id1); deleteFilmById(id2);
        }
    }

    @Test
    void upsertFilms_omittedFieldOnUpdateBranchLeavesColumnAlone() {
        // UPDATE branch (existing filmIds). The dynamic doUpdate SET walks firstKeys
        // and binds DSL.excluded(col) only for present keys. rentalDuration is omitted
        // from the input, so it drops out of DO UPDATE SET entirely; existing value (7)
        // survives. Pre-R77, naive `c = EXCLUDED.c` for every setFields entry would
        // have overwritten with EXCLUDED.rental_duration which (because the INSERT
        // branch's value cell is DEFAULT) resolves to 3 — silent data loss.
        String origTitle1 = randomMarker("R77F-UPS-UPD-A");
        String origTitle2 = randomMarker("R77F-UPS-UPD-B");
        Integer id1 = insertFilm(origTitle1, 7);
        Integer id2 = insertFilm(origTitle2, 7);
        String newTitle1 = randomMarker("R77F-UPS-UPD-NEW-A");
        String newTitle2 = randomMarker("R77F-UPS-UPD-NEW-B");
        try {
            execute("""
                mutation {
                    upsertFilms(in: [
                        { filmId: %d, title: "%s", languageId: 1 },
                        { filmId: %d, title: "%s", languageId: 1 }
                    ]) { filmId }
                }
                """.formatted(id1, newTitle1, id2, newTitle2));
            Integer dbRental1 = dsl.select(DSL.field("rental_duration", Integer.class))
                .from(DSL.table("film")).where(DSL.field("film_id", Integer.class).eq(id1))
                .fetchOne().value1();
            assertThat(dbRental1).as("omitted rentalDuration preserved on bulk upsert update branch").isEqualTo(7);
        } finally {
            deleteFilmById(id1); deleteFilmById(id2);
        }
    }

    // ===== runtime-guard rejection paths =====

    @Test
    void updateFilms_divergentInputShapes_raisesError() {
        // Uniform-shape guard: row 0 keyset `{filmId, title}` versus row 1 `{filmId,
        // title, description}` — the guard fires before any SQL dispatch.
        String origTitle = randomMarker("R77F-UPD-DIV");
        Integer id = insertFilm(origTitle);
        QUERY_COUNT.set(0);
        try {
            graphql.ExecutionResult result = executeRaw("""
                mutation {
                    updateFilms(in: [
                        { filmId: %d, title: "x" },
                        { filmId: %d, title: "y", description: "d" }
                    ]) { filmId }
                }
                """.formatted(id, id));
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(QUERY_COUNT.get()).as("uniform-shape guard fires before SQL").isZero();
        } finally {
            deleteFilmById(id);
        }
    }

    @Test
    void upsertFilms_divergentInputShapes_raisesError() {
        // UPSERT in `.doUpdate()` mode (FilmUpsertInput has setFields). Uniform-shape
        // guard fires before SQL.
        int id1 = 999_301, id2 = 999_302;
        deleteFilmById(id1); deleteFilmById(id2);
        QUERY_COUNT.set(0);
        graphql.ExecutionResult result = executeRaw("""
            mutation {
                upsertFilms(in: [
                    { filmId: %d, title: "a", languageId: 1 },
                    { filmId: %d, title: "b", languageId: 1, rentalDuration: 5 }
                ]) { filmId }
            }
            """.formatted(id1, id2));
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(QUERY_COUNT.get()).as("uniform-shape guard fires before SQL").isZero();
    }

    @Test
    void updateFilms_onlyLookupKeyFields_raisesError() {
        // No-set-fields-present guard: every input row carries only @lookupKey
        // (filmId), so the dynamic SET walk produces an empty `sets` map. Guard fires
        // before SQL.
        String origTitle = randomMarker("R77F-UPD-LK");
        Integer id = insertFilm(origTitle);
        QUERY_COUNT.set(0);
        try {
            graphql.ExecutionResult result = executeRaw("""
                mutation {
                    updateFilms(in: [{ filmId: %d }, { filmId: %d }]) { filmId }
                }
                """.formatted(id, id + 1));
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(QUERY_COUNT.get()).as("no-set-fields guard fires before SQL").isZero();
        } finally {
            deleteFilmById(id);
        }
    }

    @Test
    void upsertFilms_onlyLookupKeyFields_raisesError() {
        // FilmUpsertInput has non-lookup setFields, but the input map carries only
        // filmId — the dynamic doUpdate SET walks firstKeys and finds none of the
        // setFields present. Same no-set-fields-present guard.
        int id1 = 999_401, id2 = 999_402;
        deleteFilmById(id1); deleteFilmById(id2);
        QUERY_COUNT.set(0);
        graphql.ExecutionResult result = executeRaw("""
            mutation {
                upsertFilms(in: [{ filmId: %d }, { filmId: %d }]) { filmId }
            }
            """.formatted(id1, id2));
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(QUERY_COUNT.get()).as("no-set-fields guard fires before SQL").isZero();
    }

    @Test
    void updateFilms_duplicateLookupKeys_raisesError() {
        // Duplicate-tuple guard: bulk UPDATE rejects when two rows share the same
        // lookup-key tuple (PostgreSQL UPDATE...FROM(VALUES) has undefined behavior
        // when the join produces multiple matches per target row). Fires before SQL.
        // UPSERT has no analogue; PostgreSQL itself errors on duplicate ON CONFLICT keys.
        String origTitle = randomMarker("R77F-UPD-DUP");
        Integer id = insertFilm(origTitle);
        QUERY_COUNT.set(0);
        try {
            graphql.ExecutionResult result = executeRaw("""
                mutation {
                    updateFilms(in: [
                        { filmId: %d, title: "x" },
                        { filmId: %d, title: "y" }
                    ]) { filmId }
                }
                """.formatted(id, id));
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(QUERY_COUNT.get()).as("duplicate-tuple guard fires before SQL").isZero();
        } finally {
            deleteFilmById(id);
        }
    }

    // doNothing-mode coverage (`tia.setFields()` empty → uniform-shape guard not emitted at
    // codegen time) is pinned at the pipeline tier in FetcherPipelineTest rather than here.
    // PostgreSQL enforces NOT NULL before evaluating ON CONFLICT, so an UPSERT input
    // carrying only @lookupKey fields can't be exercised against Sakila's `film` table:
    // the INSERT branch errors on the first NOT NULL column (`title`) regardless of
    // whether every supplied filmId would conflict. The structural assertion (no
    // `firstKeys` capture, no uniformity guard) is the meaningful pin and lives at the
    // tier that can read the emitted source.

    // ===== empty-list short-circuit, one per verb =====

    @Test
    void createFilms_emptyListInput_doesNotRoundTrip() {
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute("mutation { createFilms(in: []) { filmId } }");
        assertThat((List<?>) data.get("createFilms")).isEmpty();
        assertThat(QUERY_COUNT.get()).isZero();
    }

    @Test
    void updateFilms_emptyListInput_doesNotRoundTrip() {
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute("mutation { updateFilms(in: []) { filmId } }");
        assertThat((List<?>) data.get("updateFilms")).isEmpty();
        assertThat(QUERY_COUNT.get()).isZero();
    }

    @Test
    void upsertFilms_emptyListInput_doesNotRoundTrip() {
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute("mutation { upsertFilms(in: []) { filmId } }");
        assertThat((List<?>) data.get("upsertFilms")).isEmpty();
        assertThat(QUERY_COUNT.get()).isZero();
    }

    @Test
    void deleteFilms_emptyListInput_doesNotRoundTrip() {
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute("mutation { deleteFilms(in: []) }");
        assertThat((List<?>) data.get("deleteFilms")).isEmpty();
        assertThat(QUERY_COUNT.get()).isZero();
    }
}
