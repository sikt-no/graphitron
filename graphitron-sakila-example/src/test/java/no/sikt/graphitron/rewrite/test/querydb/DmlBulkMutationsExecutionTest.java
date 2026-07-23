package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
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
 * Execution-tier coverage for bulk DML mutations against PostgreSQL.
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

        graphql = Graphitron.newGraphQL().build();
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

    private graphql.ExecutionResult run(String query) {        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
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
    void createFilms_insertsNRowsAndReturnsProjectedListInReturningOrder() {
        // Bulk INSERT emits valuesOfRows(...) inside the PK-only RETURNING transaction; the
        // rows companion re-fetches through the VALUES (idx, pk) join ordered by idx, so the
        // payload aligns one-to-one, in order, with the RETURNING result (which for
        // INSERT ... VALUES follows the input row order). containsExactly, not InAnyOrder:
        // the ordering contract is deliberate, pinned here and in the user manual.
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
            assertThat(rows).extracting(r -> r.get("title")).containsExactly(m1, m2, m3);
        } finally {
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("title").in(m1, m2, m3)).execute();
        }
    }

    @Test
    void createKeyedNodes_payloadFollowsInputOrderAgainstKeyOrder() {
        // The discriminating order fixture: keyed_node's PK is client-supplied, so the input
        // can be ordered *against* the key's natural sort order ("zz", "aa", "mm"). The payload
        // must follow the RETURNING (input) order — a scan-order accident (index or heap order)
        // would return the aa/mm/zz sort instead. This is the contract the companion's
        // ORDER BY idx makes deterministic.
        String suffix = "-" + UUID.randomUUID();
        String kZ = "zz" + suffix, kA = "aa" + suffix, kM = "mm" + suffix;
        String idZ = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("KeyedNode", kZ);
        String idA = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("KeyedNode", kA);
        String idM = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("KeyedNode", kM);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createKeyedNodes(in: [
                        { id: "%s", label: "first" },
                        { id: "%s", label: "second" },
                        { id: "%s", label: "third" }
                    ]) { label }
                }
                """.formatted(idZ, idA, idM));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("createKeyedNodes");
            assertThat(rows).extracting(r -> r.get("label"))
                .as("payload order follows the write (input) order, not the key sort order")
                .containsExactly("first", "second", "third");
        } finally {
            dsl.deleteFrom(DSL.table("keyed_node"))
                .where(DSL.field("id").in(kZ, kA, kM)).execute();
        }
    }

    @Test
    void updateFilms_missedRowShrinksPayloadToWrittenRows() {
        // Cardinality half of the contract: one payload row per *written* row. An input row
        // whose filmId matches nothing writes nothing and reports nothing through RETURNING,
        // so the payload shrinks to the written set instead of echoing the input cardinality.
        String orig = randomMarker("R489-UPD-MISS");
        Integer id = insertFilm(orig);
        int missingId = 999_901;
        deleteFilmById(missingId);
        String updated = randomMarker("R489-UPD-MISS-NEW");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilms(in: [
                        { filmId: %d, title: "%s" },
                        { filmId: %d, title: "x" }
                    ]) { title }
                }
                """.formatted(id, updated, missingId));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("updateFilms");
            assertThat(rows).extracting(r -> r.get("title")).containsExactly(updated);
        } finally {
            deleteFilmById(id);
        }
    }

    // ===== the reentry companion as a directly-assertable seam =====
    //
    // rows<Name> is a public static unit keyed by the write's RETURNING result, so its
    // row-per-write contract is assertable with a hand-built keys Result — which is the only
    // way to reach duplicate keys today: the GraphQL surfaces cannot produce them (bulk UPDATE
    // rejects duplicate lookup tuples before SQL, INSERT never repeats a PK, and PostgreSQL
    // reports each target row at most once through RETURNING). The synthetic environment
    // carries the two GraphQLContext entries the companion reads (DSLContext + the
    // GraphitronContext singleton) and an empty selection set (jOOQ renders an empty
    // projection list as SELECT *).

    private static final graphql.schema.DataFetchingFieldSelectionSet EMPTY_SELECTION =
        new graphql.schema.DataFetchingFieldSelectionSet() {
            @Override public boolean contains(String fieldGlobPattern) { return false; }
            @Override public boolean containsAnyOf(String first, String... rest) { return false; }
            @Override public boolean containsAllOf(String first, String... rest) { return false; }
            @Override public List<graphql.schema.SelectedField> getFields() { return List.of(); }
            @Override public List<graphql.schema.SelectedField> getImmediateFields() { return List.of(); }
            @Override public List<graphql.schema.SelectedField> getFields(String glob, String... rest) { return List.of(); }
            @Override public Map<String, List<graphql.schema.SelectedField>> getFieldsGroupedByResultKey() { return Map.of(); }
            @Override public Map<String, List<graphql.schema.SelectedField>> getFieldsGroupedByResultKey(String glob, String... rest) { return Map.of(); }
        };

    @Test
    void rowsUpdateFilms_duplicateKeys_yieldOnePayloadRowPerKeyInKeysOrder() {
        // Row-per-write, not row-per-distinct-key: keys [B, A, A] must produce payload
        // [B, A, A]. Under the retired keys-IN spelling the duplicate collapsed (IN dedupes)
        // and the order was the scan's; the VALUES (idx, pk) join makes both deliberate.
        String tA = randomMarker("R489-DUP-A");
        String tB = randomMarker("R489-DUP-B");
        Integer idA = insertFilm(tA);
        Integer idB = insertFilm(tB);
        var film = no.sikt.graphitron.rewrite.test.jooq.Tables.FILM;
        try {
            org.jooq.Result<org.jooq.Record1<Integer>> keys = dsl.newResult(film.FILM_ID);
            keys.add(dsl.newRecord(film.FILM_ID).value1(idB));
            keys.add(dsl.newRecord(film.FILM_ID).value1(idA));
            keys.add(dsl.newRecord(film.FILM_ID).value1(idA));
            DataFetchingEnvironment env = DataFetchingEnvironmentImpl
                .newDataFetchingEnvironment()
                .graphQLContext(GraphQLContext.newContext()
                    .of(DSLContext.class, dsl,
                        GraphitronContext.class, GraphitronContext.GraphitronContextImpl.INSTANCE)
                    .build())
                .selectionSet(EMPTY_SELECTION)
                .build();
            List<org.jooq.Record> payload =
                no.sikt.graphitron.generated.fetchers.MutationFetchers.rowsUpdateFilms(keys, env);
            // The projection always carries the reserved-alias full parent row (__src_*__), so
            // the assertion reads titles through it; the empty selection set adds nothing else.
            assertThat(payload).extracting(r -> r.get("__src_title__", String.class))
                .as("one payload row per keys row, aligned with keys order")
                .containsExactly(tB, tA, tA);
        } finally {
            deleteFilmById(idA);
            deleteFilmById(idB);
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
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
    void upsertFilms_writesNRowsAndReturnsProjectedListAcrossBothBranches() {
        // Mixes INSERT branch (novel filmId) and UPDATE branch (pre-existing filmId)
        // in a single bulk call. Single dispatched statement; `valuesOfRows`
        // followed by `.onConflict(filmId).doUpdate().set(...)`.
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
        // (the smallint NOT NULL DEFAULT 3 lands). Previously the emitter wrote typed null
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
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
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
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
    void upsertFilms_omittedFieldOnUpdateBranchLeavesColumnAlone() {
        // UPDATE branch (existing filmIds). The dynamic doUpdate SET walks firstKeys
        // and binds DSL.excluded(col) only for present keys. rentalDuration is omitted
        // from the input, so it drops out of DO UPDATE SET entirely; existing value (7)
        // survives. Previously, naive `c = EXCLUDED.c` for every setFields entry would
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
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
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
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
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
    @org.junit.jupiter.api.Disabled("R144 retires UPSERT generation pending R145.")
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

    // ===== bulk-input single-payload-carrier list-data-field DML mutations =====
    //
    // createFilmsPayload / updateFilmsPayload return FilmsPayload { films: [Film!] } — a single
    // carrier wrapping a list-shaped @table-element data field. The classifier routes the
    // bulk-input + list-data-field cell to MutationBulkDmlRecordField; the emitter batches
    // per-row DML inside one dsl.transactionResult(...), collects PKs in input order, and lets
    // the data field's BatchedTableField fetcher (Arity.MANY) run the follow-up
    // response SELECT against those PKs outside the transaction. The order-preservation
    // invariant (output.data[i] corresponds to input[i]) is the load-bearing assertion the
    // non-PK-ordered round-trip pins; any future single-statement emit refinement must preserve
    // the same assertion.

    @Test
    void bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse() {
        // N=3 inputs whose titles deliberately sort 'c', 'a', 'b' (input order) while the
        // generated film_id sequence sorts ascending. The data field's response SELECT runs
        // WHERE film_id IN (PKs in input order); the source.getValues(PK) projection iterates
        // the upstream Result in insertion order, which the per-row fetcher loop guarantees
        // matches the input list. Asserts the rendered list is in input order, not PK order.
        String m1 = randomMarker("R141-INSERT-c");
        String m2 = randomMarker("R141-INSERT-a");
        String m3 = randomMarker("R141-INSERT-b");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmsPayload(in: [
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 }
                    ]) { films { title } }
                }
                """.formatted(m1, m2, m3));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmsPayload");
            List<Map<String, Object>> films = (List<Map<String, Object>>) payload.get("films");
            assertThat(films).hasSize(3);
            assertThat(films).extracting(r -> r.get("title")).containsExactly(m1, m2, m3);
        } finally {
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("title").in(m1, m2, m3)).execute();
        }
    }

    @Test
    void bulkInsertReturnDerivedWriteTarget_roundTrips() {
        // The write target is derived from the return (createFilmsReturnDerivedPayload :
        // FilmsReturnDerivedPayload, whose data field element is the @table type Film); the input
        // FilmCreateReturnDerivedInput carries no @table. The generated statement must execute
        // identically to the @table-on-input bulk INSERT, preserving input order in the response.
        String m1 = randomMarker("R515-INSERT-c");
        String m2 = randomMarker("R515-INSERT-a");
        String m3 = randomMarker("R515-INSERT-b");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmsReturnDerivedPayload(in: [
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 }
                    ]) { films { title } }
                }
                """.formatted(m1, m2, m3));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmsReturnDerivedPayload");
            List<Map<String, Object>> films = (List<Map<String, Object>>) payload.get("films");
            assertThat(films).hasSize(3);
            assertThat(films).extracting(r -> r.get("title")).containsExactly(m1, m2, m3);
            assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").in(m1, m2, m3)))
                .as("all three return-derived INSERT rows landed in the film table")
                .isEqualTo(3);
        } finally {
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("title").in(m1, m2, m3)).execute();
        }
    }

    @Test
    void bulkInsertWithSingleRowExercisesBulkLeafPath() {
        // N=1 sanity test confirming the bulk leaf admits and emits correctly when the input
        // list has exactly one element. The classifier admits MutationBulkDmlRecordField for
        // any tia.list() == true && N >= 1, and the emitter's transactionResult lambda iterates
        // [single element] producing a Result of size 1.
        String marker = randomMarker("R141-INSERT-1");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmsPayload(in: [{ title: "%s", languageId: 1 }]) {
                        films { title }
                    }
                }
                """.formatted(marker));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmsPayload");
            List<Map<String, Object>> films = (List<Map<String, Object>>) payload.get("films");
            assertThat(films).hasSize(1);
            assertThat(films.get(0).get("title")).isEqualTo(marker);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(marker)).execute();
        }
    }

    @Test
    void bulkUpdateWithThreeRowsInNonPkOrderPreservesInputOrderInResponse() {
        // UPDATE variant of the order-preservation test, exercising the per-row UPDATE emit
        // path. Filter columns are sourced via tableInputArg.fieldBindings() (the
        // polarity-agnostic surface, read ahead of the polarity flip). Three inputs
        // whose filmIds are deliberately not in ascending order; assert the response list is
        // in input order.
        String mA = randomMarker("R141-UPDATE-A");
        String mB = randomMarker("R141-UPDATE-B");
        String mC = randomMarker("R141-UPDATE-C");
        Integer idA = insertFilm(mA);
        Integer idB = insertFilm(mB);
        Integer idC = insertFilm(mC);
        String newA = randomMarker("R141-UPDATED-A");
        String newB = randomMarker("R141-UPDATED-B");
        String newC = randomMarker("R141-UPDATED-C");
        // Input order: C, A, B (deliberately not ascending PK order: idA < idB < idC).
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilmsPayload(in: [
                        { filmId: %d, title: "%s" },
                        { filmId: %d, title: "%s" },
                        { filmId: %d, title: "%s" }
                    ]) { films { filmId title } }
                }
                """.formatted(idC, newC, idA, newA, idB, newB));
            Map<String, Object> payload = (Map<String, Object>) data.get("updateFilmsPayload");
            List<Map<String, Object>> films = (List<Map<String, Object>>) payload.get("films");
            assertThat(films).hasSize(3);
            assertThat(films).extracting(r -> r.get("title")).containsExactly(newC, newA, newB);
        } finally {
            deleteFilmById(idA);
            deleteFilmById(idB);
            deleteFilmById(idC);
        }
    }

    // ===== composite-PK @nodeId-decoded @lookupKey on DML inputs =====
    //
    // Headline forcing-function execution proof: composite-PK DELETE keyed by an opaque
    // NodeId on a @table input field. The single-row path drives
    // TypeFetcherGenerator.buildLookupWhereSingleRow's DecodedRecordGroup arm (one decode
    // local lifted to postInGuard with ThrowOnMismatch null handling, N positional
    // value<i>() reads into a chained .eq predicate). The bulk path drives
    // buildBulkLookupRowIn's block-lambda form (decode call lifted inside the stream
    // lambda body). These are the load-bearing emitter paths for this shape;
    // previously the classifier rejected the shape outright.

    private void seedFilmActor(int actorId, int filmId) {
        dsl.insertInto(DSL.table("film_actor"))
            .set(DSL.field("actor_id"), actorId)
            .set(DSL.field("film_id"), filmId)
            .execute();
    }

    private void cleanupFilmActor(int actorId, int filmId) {
        dsl.deleteFrom(DSL.table("film_actor"))
            .where(DSL.field("actor_id", Integer.class).eq(actorId))
            .and(DSL.field("film_id", Integer.class).eq(filmId))
            .execute();
    }

    private int countFilmActor(int actorId, int filmId) {
        return dsl.fetchCount(
            DSL.selectOne().from(DSL.table("film_actor"))
                .where(DSL.field("actor_id", Integer.class).eq(actorId))
                .and(DSL.field("film_id", Integer.class).eq(filmId)));
    }

    @Test
    void deleteFilmActorByNodeId_singleRow_deletesByDecodedComposite() {
        // The headline `slettRegelverksamling`-shaped proof: composite-PK DELETE keyed by
        // a single NodeId-decoded carrier. The generated body decodes id once into a
        // Record2<Integer, Integer> in postInGuard, throws on type mismatch, then emits
        // `where(actor_id.eq(__lookupKey0.value1()).and(film_id.eq(__lookupKey0.value2())))`.
        // Uses pair (1, 4) — not in the init.sql film_actor seed, so cleanup is local.
        seedFilmActor(1, 4);
        String nodeId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 4);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteFilmActorByNodeId(in: { id: "%s" })
                }
                """.formatted(nodeId));
            assertThat(data.get("deleteFilmActorByNodeId")).isNotNull();
            assertThat(countFilmActor(1, 4)).isZero();
        } finally {
            cleanupFilmActor(1, 4);
        }
    }

    @Test
    void deleteFilmActorsByNodeId_bulkRows_deletesAllViaRowIn() {
        // Bulk DELETE keyed by N NodeIds, each decoded inside the stream lambda. The
        // generated body emits
        // `DSL.row(actor_id, film_id).in(in.stream().map(row -> { ... __bulkKey0 = decode(...); ...
        //   return DSL.row(DSL.val(__bulkKey0.value1(), ...), DSL.val(__bulkKey0.value2(), ...));
        // }).toList())`.
        // Uses pairs (2, 3) and (3, 4) — neither in the init.sql seed.
        seedFilmActor(2, 3);
        seedFilmActor(3, 4);
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 3);
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 3, 4);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteFilmActorsByNodeId(in: [{ id: "%s" }, { id: "%s" }])
                }
                """.formatted(id1, id2));
            assertThat((List<?>) data.get("deleteFilmActorsByNodeId")).hasSize(2);
            assertThat(countFilmActor(2, 3)).isZero();
            assertThat(countFilmActor(3, 4)).isZero();
        } finally {
            cleanupFilmActor(2, 3);
            cleanupFilmActor(3, 4);
        }
    }

    @Test
    void createKeyedNode_singlePkNodeIdDecodedInsert_writesRow() {
        // Single-PK INSERT keyed by a client-supplied NodeId. Drives
        // buildInsertDecodeLocals (one preGuard decode local per NodeId-bearing
        // carrier) plus buildPerCellValueList's NodeIdDecodeKeys arm
        // (`__insertKey_<fi>.value1()` read into the values cell). Classifies as
        // ColumnField with NodeIdDecodeKeys extraction; previously the
        // MutationInputResolver rejected this carrier outright.
        String rowKey = "R130-INSERT-" + UUID.randomUUID();
        String nodeId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("KeyedNode", rowKey);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createKeyedNode(in: { id: "%s", label: "first" })
                }
                """.formatted(nodeId));
            assertThat(data.get("createKeyedNode")).isNotNull();

            int rows = dsl.fetchCount(
                DSL.selectOne().from(DSL.table("keyed_node"))
                    .where(DSL.field("id", String.class).eq(rowKey)));
            assertThat(rows).isEqualTo(1);
            String label = dsl.select(DSL.field("label", String.class))
                .from(DSL.table("keyed_node"))
                .where(DSL.field("id", String.class).eq(rowKey))
                .fetchOne().value1();
            assertThat(label).isEqualTo("first");
        } finally {
            dsl.deleteFrom(DSL.table("keyed_node"))
                .where(DSL.field("id", String.class).eq(rowKey)).execute();
        }
    }

    @Test
    void createKeyedNode_wrongTypeNodeId_surfacesError() {
        // Type-mismatch on the INSERT-arm NodeId decode lift. The generated body's
        // `if (in.containsKey("id") && __insertKey_<fi> == null) throw ...` branch
        // surfaces a GraphqlErrorException via the fetcher's try/catch wrapper.
        // No row is written.
        String wrong = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        int before = dsl.fetchCount(DSL.selectOne().from(DSL.table("keyed_node")));
        graphql.ExecutionResult result = executeRaw("""
            mutation {
                createKeyedNode(in: { id: "%s", label: "wrong" })
            }
            """.formatted(wrong));
        assertThat(result.getErrors()).isNotEmpty();
        int after = dsl.fetchCount(DSL.selectOne().from(DSL.table("keyed_node")));
        assertThat(after).isEqualTo(before);
    }

    @Test
    void deleteFilmActorByNodeId_wrongTypeNodeId_surfacesError() {
        // ThrowOnMismatch contract on the lookup-key decode path: a NodeId encoded for a
        // different type fails decode (returns null), and the per-row throw in the
        // generated postInGuard surfaces as a GraphqlErrorException via the
        // try/catch wrapper. The seeded row is untouched.
        // Uses pair (3, 3) — not in the init.sql seed.
        seedFilmActor(3, 3);
        String wrong = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        try {
            graphql.ExecutionResult result = executeRaw("""
                mutation {
                    deleteFilmActorByNodeId(in: { id: "%s" })
                }
                """.formatted(wrong));
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(countFilmActor(3, 3)).isEqualTo(1);
        } finally {
            cleanupFilmActor(3, 3);
        }
    }

    // ===== multiRow DELETE broadcast =====

    @Test
    void deleteFilmsByReleaseYear_multiRowBroadcastsAcrossInputCardinality() {
        // End-to-end proof of the multiRow opt-out. The mutation declares
        // `@mutation(typeName: DELETE, multiRow: true)` and filters on release_year
        // (non-PK). With three pre-inserted rows sharing one release_year, a single
        // input row deletes all three — |affected rows| > |input rows| is the
        // explicit, opted-in semantics.
        Integer releaseYear = 2125;
        // Pre-clean any seed rows on this year so the test owns the row count.
        dsl.deleteFrom(DSL.table("film"))
            .where(DSL.field("release_year", Integer.class).eq(releaseYear))
            .execute();
        Integer id1 = insertFilmWithYear(randomMarker("R144-MULTIROW-A"), releaseYear);
        Integer id2 = insertFilmWithYear(randomMarker("R144-MULTIROW-B"), releaseYear);
        Integer id3 = insertFilmWithYear(randomMarker("R144-MULTIROW-C"), releaseYear);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteFilmsByReleaseYear(in: [
                        { releaseYear: %d }
                    ])
                }
                """.formatted(releaseYear));
            List<?> deletedIds = (List<?>) data.get("deleteFilmsByReleaseYear");
            assertThat(deletedIds)
                .as("one input row broadcasts to three database rows under multiRow: true")
                .hasSize(3);
            int remaining = dsl.fetchCount(DSL.selectFrom(DSL.table("film"))
                .where(DSL.field("release_year", Integer.class).eq(releaseYear)));
            assertThat(remaining).isZero();
        } finally {
            deleteFilmById(id1);
            deleteFilmById(id2);
            deleteFilmById(id3);
        }
    }

    private Integer insertFilmWithYear(String title, int releaseYear) {
        return dsl.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("language_id"), (short) 1)
            .set(DSL.field("release_year", Integer.class), releaseYear)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    // ===== payload-returning DELETE (ID-typed carrier shape) =====
    //
    // deleteFilmsIdCarrier returns DeletedFilmsIdPayload { deletedIds: [ID!] }, where the
    // carrier's data field classifies as ChildField.SingleRecordIdFieldFromReturning carrying
    // the per-Film NodeId encoder. The fetcher emits `(env) -> { for r in source: __ids.add(
    // encodeFilm(r.get(...))) }`, producing the list of encoded PKs in input order. This is the
    // deletion-safe carrier shape: the PK comes straight off the RETURNING record, no follow-up
    // read against the gone row. (The @table-element DELETE carrier and its execution proof were
    // removed: a full @table projection off a deleted row is impossible.)

    @Test
    void deleteFilmsIdCarrier_returnsEncodedNodeIdsOfDeletedRows() {
        String m1 = randomMarker("R156-ID-a");
        String m2 = randomMarker("R156-ID-b");
        Integer id1 = insertFilm(m1);
        Integer id2 = insertFilm(m2);
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteFilmsIdCarrier(in: [
                        { filmId: %d },
                        { filmId: %d }
                    ]) { deletedIds }
                }
                """.formatted(id1, id2));
            Map<String, Object> payload = (Map<String, Object>) data.get("deleteFilmsIdCarrier");
            List<String> deletedIds = (List<String>) payload.get("deletedIds");
            String encoded1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", id1);
            String encoded2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", id2);
            assertThat(deletedIds).containsExactly(encoded1, encoded2);
            int remaining = dsl.fetchCount(DSL.selectFrom(DSL.table("film"))
                .where(DSL.field("film_id", Integer.class).in(id1, id2)));
            assertThat(remaining).as("rows are actually deleted").isZero();
        } finally {
            // best-effort cleanup of rows that may have survived (shouldn't happen on success)
            dsl.deleteFrom(DSL.table("film"))
                .where(DSL.field("film_id", Integer.class).in(id1, id2)).execute();
        }
    }

    // ===== UK-covering single-row DELETE =====
    //
    // storage_bin is the one public-schema table with a UNIQUE constraint (`code`) distinct from
    // its `bin_id` PK. deleteStorageBinByCode covers `code` only, so the DeleteRowsWalker's
    // PK-or-UK match lands on the UniqueKey arm rather than the PK — the UK-covering single-row
    // delete every other Sakila DELETE fixture (all PK-keyed) leaves unproven at execution tier.
    // The emitted statement is `DELETE FROM storage_bin WHERE code = ? RETURNING bin_id`, and the
    // `: ID` return encodes the matched bin_id as a StorageBin NodeId. The UK execution case for
    // UPDATE is deferred; this is the DELETE-side proof.

    private int insertStorageBin(String code, String label) {
        return dsl.insertInto(DSL.table("storage_bin"))
            .set(DSL.field("code"), code)
            .set(DSL.field("label"), label)
            .returningResult(DSL.field("bin_id", Integer.class))
            .fetchOne().value1();
    }

    private int countStorageBin(int binId) {
        return dsl.fetchCount(DSL.selectOne().from(DSL.table("storage_bin"))
            .where(DSL.field("bin_id", Integer.class).eq(binId)));
    }

    @Test
    void deleteStorageBinByCode_singleRow_deletesRowMatchedByUniqueKey() {
        // Two rows differing only by their UNIQUE `code`. The mutation covers `code` (not the
        // bin_id PK), so the row is identified by the UniqueKey arm. The sibling with a different
        // code must survive — proof the WHERE keys on the UK value, not a blanket delete.
        String code = randomMarker("R266-UK");
        String survivorCode = randomMarker("R266-UK-KEEP");
        int targetId = insertStorageBin(code, "target");
        int survivorId = insertStorageBin(survivorCode, "survivor");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteStorageBinByCode(in: { code: "%s" })
                }
                """.formatted(code));
            // The returned NodeId encodes the matched row's PK (bin_id), proving RETURNING
            // projected the PK even though the WHERE keyed on the UNIQUE `code`.
            String returned = (String) data.get("deleteStorageBinByCode");
            assertThat(returned)
                .isEqualTo(no.sikt.graphitron.generated.util.NodeIdEncoder.encode("StorageBin", targetId));
            assertThat(countStorageBin(targetId)).as("row matched by its UK value is deleted").isZero();
            assertThat(countStorageBin(survivorId)).as("sibling with a different UK survives").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("storage_bin"))
                .where(DSL.field("code").in(code, survivorCode)).execute();
        }
    }

    @Test
    void deleteStorageBinByCodePayload_fieldTableWriteTarget_roundTrips() {
        // The payload-returning sibling of deleteStorageBinByCode. The write target is named on
        // the field (@mutation(table: "storage_bin")); the input carries no @table. This is the shape
        // that rejected before the DmlEmitted @mutation(table:) grounding — a field-derived
        // payload-returning DELETE grounded no producer binding, so the carrier never registered and
        // the field fell through to the generic "return type not yet supported". It must now
        // classify, delete the UK-matched row, and echo its bin_id as a StorageBin NodeId inside the
        // payload's deletedId.
        String code = randomMarker("R514-payload");
        String survivorCode = randomMarker("R514-payload-KEEP");
        int targetId = insertStorageBin(code, "target");
        int survivorId = insertStorageBin(survivorCode, "survivor");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    deleteStorageBinByCodePayload(in: { code: "%s" }) { deletedId }
                }
                """.formatted(code));
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) data.get("deleteStorageBinByCodePayload");
            assertThat((String) payload.get("deletedId"))
                .as("the field-named write target's PK is projected through RETURNING and encoded")
                .isEqualTo(no.sikt.graphitron.generated.util.NodeIdEncoder.encode("StorageBin", targetId));
            assertThat(countStorageBin(targetId)).as("row matched by its UK value is deleted").isZero();
            assertThat(countStorageBin(survivorId)).as("sibling with a different UK survives").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("storage_bin"))
                .where(DSL.field("code").in(code, survivorCode)).execute();
        }
    }
}
