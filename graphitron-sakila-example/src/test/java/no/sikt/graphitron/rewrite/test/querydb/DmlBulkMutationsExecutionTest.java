package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.FetcherErrorLog;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.generated.schema.ZeroRowsMatchedException;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for bulk DML mutations against PostgreSQL.
 * Each verb's bulk arm (INSERT/UPDATE/DELETE) is exercised end-to-end via
 * the {@code createFilms} / {@code updateFilms} / {@code deleteFilms} Mutation
 * fields wired in the Sakila schema. Pinned in this tier because the bulk arms
 * have execution-only semantics not visible at compile or pipeline tier: an
 * absent field takes its column default, alone or beside rows that supply it
 * (INSERT); the dynamic SET walk (UPDATE); the uniform-shape / duplicate-tuple
 * guards (UPDATE), which reject the input and leave the database unchanged;
 * and the typed empty-list short-circuit.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class DmlBulkMutationsExecutionTest {

    static DSLContext dsl;
    static String dbUrl;
    static String dbUser;
    static String dbPassword;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

    @BeforeAll
    static void startDatabase() throws Exception {
        dbUrl = SakilaDatabase.url();
        dbUser = SakilaDatabase.user();
        dbPassword = SakilaDatabase.password();

        dsl = DSL.using(dbUrl, dbUser, dbPassword);

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                }
            }));

        graphql = Graphitron.newGraphQL().build();
    }


    private Map<String, Object> execute(String query) {
        return execute(dsl, query);
    }

    private Map<String, Object> execute(DSLContext ctx, String query) {
        var result = run(ctx, query);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private graphql.ExecutionResult executeRaw(String query) { return run(dsl, query); }

    private graphql.ExecutionResult executeRaw(DSLContext ctx, String query) { return run(ctx, query); }

    private graphql.ExecutionResult run(DSLContext ctx, String query) {
        var input = Graphitron.newExecutionInput(ctx, "{}", "test-user").query(query).build();
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
        // must follow the RETURNING (input) order; a scan-order accident (index or heap order)
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
    // row-per-write contract is assertable with a hand-built keys Result, the only way to
    // reach duplicate keys: the GraphQL surfaces cannot produce them (bulk UPDATE
    // rejects duplicate lookup tuples before SQL, INSERT never repeats a PK, and PostgreSQL
    // reports each target row at most once through RETURNING). The synthetic environment
    // carries the two GraphQLContext entries the companion reads (DSLContext + the
    // GraphitronContext singleton) and a one-field selection ({@code filmId}), because the
    // projection is fully selection-gated: an empty selection projects only the row-present
    // sentinel, and the assertion below needs a real column to identify rows by.

    private static final graphql.schema.SelectedField FILM_ID_FIELD =
        new graphql.schema.SelectedField() {
            @Override public String getName() { return "filmId"; }
            @Override public String getQualifiedName() { return "filmId"; }
            @Override public String getFullyQualifiedName() { return "Film.filmId"; }
            @Override public List<graphql.schema.GraphQLObjectType> getObjectTypes() { return List.of(); }
            @Override public List<String> getObjectTypeNames() { return List.of("Film"); }
            @Override public List<graphql.schema.GraphQLFieldDefinition> getFieldDefinitions() { return List.of(); }
            @Override public graphql.schema.GraphQLOutputType getType() { throw new UnsupportedOperationException(); }
            @Override public Map<String, Object> getArguments() { return Map.of(); }
            @Override public int getLevel() { return 1; }
            @Override public boolean isConditional() { return false; }
            @Override public String getAlias() { return null; }
            @Override public String getResultKey() { return "filmId"; }
            @Override public graphql.schema.SelectedField getParentField() { return null; }
            @Override public graphql.schema.DataFetchingFieldSelectionSet getSelectionSet() { return null; }
        };

    private static final graphql.schema.DataFetchingFieldSelectionSet FILM_ID_SELECTION =
        new graphql.schema.DataFetchingFieldSelectionSet() {
            @Override public boolean contains(String fieldGlobPattern) { return "filmId".equals(fieldGlobPattern); }
            @Override public boolean containsAnyOf(String first, String... rest) { return contains(first); }
            @Override public boolean containsAllOf(String first, String... rest) { return contains(first) && rest.length == 0; }
            @Override public List<graphql.schema.SelectedField> getFields() { return List.of(FILM_ID_FIELD); }
            @Override public List<graphql.schema.SelectedField> getImmediateFields() { return List.of(FILM_ID_FIELD); }
            @Override public List<graphql.schema.SelectedField> getFields(String glob, String... rest) { return getFields(); }
            @Override public Map<String, List<graphql.schema.SelectedField>> getFieldsGroupedByResultKey() { return Map.of("filmId", List.of(FILM_ID_FIELD)); }
            @Override public Map<String, List<graphql.schema.SelectedField>> getFieldsGroupedByResultKey(String glob, String... rest) { return getFieldsGroupedByResultKey(); }
        };

    @Test
    void rowsUpdateFilms_duplicateKeys_yieldOnePayloadRowPerKeyInKeysOrder() {
        // Row-per-write, not row-per-distinct-key: keys [B, A, A] must produce payload
        // [B, A, A]. A keys-IN spelling would collapse the duplicate (IN dedupes) and leave
        // the order to the scan; the VALUES (idx, pk) join makes both deliberate.
        String tA = randomMarker("R489-DUP-A");
        String tB = randomMarker("R489-DUP-B");
        Integer idA = insertFilm(tA);
        Integer idB = insertFilm(tB);
        var film = no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
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
                .selectionSet(FILM_ID_SELECTION)
                .build();
            List<org.jooq.Record> payload =
                no.sikt.graphitron.generated.fetchers.MutationFetchers.rowsUpdateFilms(keys, env);
            // The one-field selection projects film_id through its ordinary column arm, giving
            // each payload row the identity the assertion needs to pin what this test is about:
            // one payload row per keys row, in keys order.
            assertThat(payload).extracting(r -> r.get(film.FILM_ID))
                .as("one payload row per keys row, aligned with keys order")
                .containsExactly(idB, idA, idA);
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
    void deleteFilms_deletesNRowsByLookupKeyTuple() {
        // A key-identified bulk DELETE returning [ID!]! runs one DELETE ... RETURNING per input row
        // inside one transaction and encodes the returned keys in input order.
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

    @Test
    void deleteFilms_existingAndMissingInNonKeyOrder_answersBothInInputOrder() {
        // The direct [ID!] DELETE is idempotent like the payload one: the missing key is answered
        // with the id the client sent, in its input slot, and the existing row is deleted.
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("idempotent-direct"));

            Map<String, Object> data = execute(tx, """
                mutation { deleteFilms(in: [{ filmId: %d }, { filmId: %d }]) }
                """.formatted(MISSING_FILM_ID, existing));

            assertThat((List<String>) data.get("deleteFilms"))
                .containsExactly(filmId(MISSING_FILM_ID), filmId(existing));
            assertThat(countFilm(tx, existing)).isZero();
        });
    }

    @Test
    void deleteFilmsDirectFailOnMissing_missingKey_rollsBackTheExistingRow() {
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("strict-direct"));

            graphql.ExecutionResult result = executeRaw(tx, """
                mutation { deleteFilmsDirectFailOnMissing(in: [{ filmId: %d }, { filmId: %d }]) }
                """.formatted(existing, MISSING_FILM_ID));

            assertThat(result.getErrors()).isNotEmpty();
            assertThat(countFilm(tx, existing)).as("the existing row's delete rolls back").isEqualTo(1);
        });
    }

    // ===== INSERT missing-vs-null =====

    @Test
    void createFilms_omittedFieldUsesColumnDefault() {
        // Neither row supplies rentalDuration, so the column is left out of the INSERT and its
        // smallint NOT NULL DEFAULT 3 lands; an unconditional typed-null write would surface an
        // integrity-constraint violation instead.
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

    // ===== INSERT with absent fields: the column default lands =====

    @Test
    void createFilm_omittedFieldTakesTheColumnDefault() {
        String m = randomMarker("INS-OMIT-ONE");
        try {
            Map<String, Object> data = execute("""
                mutation { createFilm(in: { title: "%s", languageId: 1 }) { rentalDuration } }
                """.formatted(m));
            Map<String, Object> film = (Map<String, Object>) data.get("createFilm");
            assertThat(((Number) film.get("rentalDuration")).intValue()).isEqualTo(3);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(m)).execute();
        }
    }

    @Test
    void createFilms_uniformRowsOmittingAFieldTakeTheColumnDefault() {
        String m1 = randomMarker("INS-OMIT-UNI-A");
        String m2 = randomMarker("INS-OMIT-UNI-B");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilms(in: [
                        { title: "%s", languageId: 1 },
                        { title: "%s", languageId: 1 }
                    ]) { rentalDuration }
                }
                """.formatted(m1, m2));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("createFilms");
            assertThat(rows).extracting(r -> ((Number) r.get("rentalDuration")).intValue())
                .containsExactly(3, 3);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").in(m1, m2)).execute();
        }
    }

    @Test
    void createFilms_mixedRowsKeepSuppliedValuesAndDefaultTheRest() {
        // The one statement has one column list, the union of what the rows supply; the row that
        // leaves rentalDuration out takes DEFAULT in that one cell.
        String m1 = randomMarker("INS-OMIT-MIX-A");
        String m2 = randomMarker("INS-OMIT-MIX-B");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilms(in: [
                        { title: "%s", languageId: 1, rentalDuration: 5 },
                        { title: "%s", languageId: 1 }
                    ]) { rentalDuration }
                }
                """.formatted(m1, m2));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("createFilms");
            assertThat(rows).extracting(r -> ((Number) r.get("rentalDuration")).intValue())
                .as("in input order")
                .containsExactly(5, 3);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").in(m1, m2)).execute();
        }
    }

    @Test
    void createFilmsPayload_mixedRowsKeepSuppliedValuesAndDefaultTheRest() {
        // The payload-returning bulk INSERT issues one statement per row, so each row names only
        // what it supplies, even when the rows differ.
        String m1 = randomMarker("INS-OMIT-PAY-A");
        String m2 = randomMarker("INS-OMIT-PAY-B");
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilmsPayload(in: [
                        { title: "%s", languageId: 1, rentalDuration: 5 },
                        { title: "%s", languageId: 1 }
                    ]) { films { rentalDuration } }
                }
                """.formatted(m1, m2));
            Map<String, Object> payload = (Map<String, Object>) data.get("createFilmsPayload");
            List<Map<String, Object>> films = (List<Map<String, Object>>) payload.get("films");
            assertThat(films).extracting(r -> ((Number) r.get("rentalDuration")).intValue())
                .containsExactly(5, 3);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").in(m1, m2)).execute();
        }
    }

    // The film_bookmark rows below carry no marker to clean up by, so each case runs in a
    // transaction that rolls back.

    @Test
    void createFilmBookmarks_severalEmptyRowsInsertOneRowEach() {
        // No row supplies a field, so the column union is empty; jOOQ would render that as one
        // `default values` row. The emitter names every column with DEFAULT cells instead.
        inRolledBackTransaction(tx -> {
            Map<String, Object> data = execute(tx, """
                mutation { createFilmBookmarks(in: [{}, {}]) { bookmarkId note } }
                """);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("createFilmBookmarks");
            assertThat(rows).hasSize(2)
                .extracting(r -> r.get("bookmarkId")).doesNotHaveDuplicates().doesNotContainNull();
        });
    }

    @Test
    void createFilmBookmarks_oneEmptyRowInsertsOneRow() {
        inRolledBackTransaction(tx -> {
            Map<String, Object> data = execute(tx, """
                mutation { createFilmBookmarks(in: [{}]) { bookmarkId } }
                """);
            assertThat((List<Map<String, Object>>) data.get("createFilmBookmarks")).hasSize(1);
        });
    }

    @Test
    void insertBookmarkOverlap_sharedColumnWithNoWriterPresentTakesItsDefault() {
        // bookmarked_film is written by both bookmarkedFilm and filmRef; with both absent the
        // column is left out, rather than taking a DEFAULT cell, and its default (film 3) lands.
        inRolledBackTransaction(tx -> {
            Map<String, Object> data = execute(tx, """
                mutation { insertBookmarkOverlap(in: { note: "overlap" }) { bookmarkedFilm note } }
                """);
            Map<String, Object> bookmark = (Map<String, Object>) data.get("insertBookmarkOverlap");
            assertThat(bookmark).containsEntry("note", "overlap").containsEntry("bookmarkedFilm", 3);
        });
    }

    @Test
    void insertBookmarkOverlap_loneExplicitNullReferenceWritesNull() {
        // filmRef sent as an explicit null is a present writer of bookmarked_film with nothing to
        // agree on: unlike the omitted case above, the column is named and bound to NULL.
        inRolledBackTransaction(tx -> {
            Map<String, Object> data = execute(tx, """
                mutation { insertBookmarkOverlap(in: { filmRef: null, note: "overlap-null" }) { bookmarkedFilm note } }
                """);
            Map<String, Object> bookmark = (Map<String, Object>) data.get("insertBookmarkOverlap");
            assertThat(bookmark).containsEntry("note", "overlap-null").containsEntry("bookmarkedFilm", null);
        });
    }

    @Test
    void insertBookmarkOverlaps_explicitNullReferenceBindsNullWhereOmittedTakesTheGapCell() {
        // Three rows that differ only in filmRef: a value, an explicit null, nothing. The explicit
        // null binds a typed NULL and the omission takes the DEFAULT gap cell; bookmarked_film
        // defaults to film 3 in init.sql, so the stored rows tell the two apart.
        String film1 = NodeIdEncoder.encode("Film", 1);
        inRolledBackTransaction(tx -> {
            Map<String, Object> data = execute(tx, """
                mutation {
                    insertBookmarkOverlaps(in: [
                        { filmRef: "%s", note: "bulk-value" },
                        { filmRef: null, note: "bulk-null" },
                        { note: "bulk-omitted" }
                    ]) { bookmarkedFilm note }
                }
                """.formatted(film1));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("insertBookmarkOverlaps");
            Map<Object, Object> filmByNote = new java.util.HashMap<>();
            rows.forEach(r -> filmByNote.put(r.get("note"), r.get("bookmarkedFilm")));
            assertThat(filmByNote)
                .containsEntry("bulk-value", 1)
                .containsEntry("bulk-null", null)
                .containsEntry("bulk-omitted", 3)
                .hasSize(3);
        });
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

    // ===== runtime-guard rejection paths =====

    @Test
    void updateFilms_divergentInputShapes_raisesError() {
        // Uniform-shape guard: row 0 keyset `{filmId, title}` versus row 1 `{filmId,
        // title, description}`; the guard fires before any SQL dispatch.
        String origTitle = randomMarker("R77F-UPD-DIV");
        Integer id = insertFilm(origTitle);
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
            assertFilmUnchanged(id, origTitle);
        } finally {
            deleteFilmById(id);
        }
    }

    @Test
    void updateFilms_duplicateLookupKeys_raisesError() {
        // Duplicate-tuple guard: bulk UPDATE rejects when two rows share the same
        // lookup-key tuple (PostgreSQL UPDATE...FROM(VALUES) has undefined behavior
        // when the join produces multiple matches per target row). Fires before SQL.
        String origTitle = randomMarker("R77F-UPD-DUP");
        Integer id = insertFilm(origTitle);
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
            assertFilmUnchanged(id, origTitle);
        } finally {
            deleteFilmById(id);
        }
    }

    /** A rejected bulk update leaves the seeded film exactly as {@link #insertFilm(String)} wrote it. */
    private void assertFilmUnchanged(int filmId, String origTitle) {
        var row = dsl.select(DSL.field("title", String.class), DSL.field("description", String.class))
            .from(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId))
            .fetchOne();
        assertThat(row).as("the film survives the rejected update").isNotNull();
        assertThat(row.value1()).as("rejected update leaves the title unwritten").isEqualTo(origTitle);
        assertThat(row.value2()).as("rejected update leaves the description unwritten").isNull();
    }

    // ===== empty-list short-circuit, one per verb =====

    @Test
    void createFilms_emptyListInput_returnsEmptyList() {
        Map<String, Object> data = execute("mutation { createFilms(in: []) { filmId } }");
        assertThat((List<?>) data.get("createFilms")).isEmpty();
    }

    @Test
    void updateFilms_emptyListInput_returnsEmptyList() {
        Map<String, Object> data = execute("mutation { updateFilms(in: []) { filmId } }");
        assertThat((List<?>) data.get("updateFilms")).isEmpty();
    }

    @Test
    void deleteFilms_emptyListInput_returnsEmptyList() {
        Map<String, Object> data = execute("mutation { deleteFilms(in: []) }");
        assertThat((List<?>) data.get("deleteFilms")).isEmpty();
    }

    // ===== bulk-input single-payload-carrier list-data-field DML mutations =====
    //
    // createFilmsPayload / updateFilmsPayload return FilmsPayload { films: [Film!] }: a single
    // carrier wrapping a list-shaped @table-element data field. The classifier routes the
    // bulk-input + list-data-field cell to MutationBulkDmlRecordField; the emitter batches
    // per-row DML inside one dsl.transactionResult(...), collects PKs in input order, and lets
    // the data field's BatchedTableField fetcher (Arity.MANY) run the follow-up
    // response SELECT against those PKs outside the transaction. The order-preservation
    // invariant (output.data[i] corresponds to input[i]) is the load-bearing assertion the
    // non-PK-ordered round-trip pins.

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
        // path. Filter columns are sourced via tableInputArg.fieldBindings(). Three inputs
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
    // Composite-PK DELETE keyed by an opaque NodeId on a @table input field. The single-row
    // path drives TypeFetcherGenerator.buildLookupWhereSingleRow's DecodedRecordGroup arm
    // (one decode local lifted to postInGuard with ThrowOnMismatch null handling, N positional
    // value<i>() reads into a chained .eq predicate). The bulk path drives
    // buildBulkLookupRowIn's block-lambda form (decode call lifted inside the stream
    // lambda body). These are the load-bearing emitter paths for this shape.

    // These cases seed film_actor pairs that init.sql does not, and reader classes elsewhere in
    // the module assert exact actor and film lists over the seeded table. On the local-db path
    // every class shares one database, so a seed committed and later deleted is visible to a
    // concurrent reader in between. Each case therefore runs seed, mutation and assertions inside
    // one transaction that always rolls back: the rollback is the cleanup, and no other
    // connection ever sees the rows.

    /** Thrown at the end of {@link #inRolledBackTransaction} to make jOOQ roll back. */
    private static final class RollbackSentinel extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RollbackSentinel() { super(null, null, false, false); }
    }

    /**
     * Runs {@code body} against a {@link DSLContext} bound to one transaction on the class's
     * connection, then rolls the transaction back. Any exception or assertion failure from the
     * body propagates as itself.
     */
    private void inRolledBackTransaction(Consumer<DSLContext> body) {
        try {
            dsl.transaction(cfg -> {
                body.accept(DSL.using(cfg));
                throw new RollbackSentinel();
            });
        } catch (RollbackSentinel rolledBack) {
            // expected: every case ends here
        }
    }

    private void seedFilmActor(DSLContext tx, int actorId, int filmId) {
        tx.insertInto(DSL.table("film_actor"))
            .set(DSL.field("actor_id"), actorId)
            .set(DSL.field("film_id"), filmId)
            .execute();
        assertThat(countFilmActor(tx, actorId, filmId)).isEqualTo(1);
        try (var other = DSL.using(dbUrl, dbUser, dbPassword)) {
            assertThat(countFilmActor(other, actorId, filmId))
                .as("film_actor (%d, %d) seeded inside the transaction is visible to another connection",
                    actorId, filmId)
                .isZero();
        }
    }

    private static int countFilmActor(DSLContext ctx, int actorId, int filmId) {
        return ctx.fetchCount(
            DSL.selectOne().from(DSL.table("film_actor"))
                .where(DSL.field("actor_id", Integer.class).eq(actorId))
                .and(DSL.field("film_id", Integer.class).eq(filmId)));
    }

    @Test
    void deleteFilmActorByNodeId_singleRow_deletesByDecodedComposite() {
        // Composite-PK DELETE keyed by a single NodeId-decoded carrier. The generated
        // body decodes id once into a
        // Record2<Integer, Integer> in postInGuard, throws on type mismatch, then emits
        // `where(actor_id.eq(__lookupKey0.value1()).and(film_id.eq(__lookupKey0.value2())))`.
        // Uses pair (1, 4), not in the init.sql film_actor seed.
        inRolledBackTransaction(tx -> {
            seedFilmActor(tx, 1, 4);
            String nodeId = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 4);
            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmActorByNodeId(in: { id: "%s" })
                }
                """.formatted(nodeId));
            assertThat(data.get("deleteFilmActorByNodeId")).isNotNull();
            assertThat(countFilmActor(tx, 1, 4)).isZero();
        });
    }

    @Test
    void deleteFilmActorsByNodeId_bulkRows_deletesAllPerRow() {
        // Bulk DELETE keyed by N NodeIds, each decoded once per row inside the per-row loop, which
        // runs one DELETE ... RETURNING per input row.
        // Uses pairs (2, 3) and (3, 4), neither in the init.sql seed.
        inRolledBackTransaction(tx -> {
            seedFilmActor(tx, 2, 3);
            seedFilmActor(tx, 3, 4);
            String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 3);
            String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 3, 4);
            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmActorsByNodeId(in: [{ id: "%s" }, { id: "%s" }])
                }
                """.formatted(id1, id2));
            assertThat((List<?>) data.get("deleteFilmActorsByNodeId")).hasSize(2);
            assertThat(countFilmActor(tx, 2, 3)).isZero();
            assertThat(countFilmActor(tx, 3, 4)).isZero();
        });
    }

    @Test
    void deleteFilmActorByNodeId_missingKey_answersTheSentIdRatherThanNull() {
        // Pair (8, 9) is not in the init.sql seed.
        inRolledBackTransaction(tx -> {
            String missing = NodeIdEncoder.encode("FilmActor", 8, 9);

            Map<String, Object> data = execute(tx, """
                mutation { deleteFilmActorByNodeId(in: { id: "%s" }) }
                """.formatted(missing));

            assertThat(data.get("deleteFilmActorByNodeId")).isEqualTo(missing);
        });
    }

    @Test
    void deleteFilmActorsByNodeId_missingThenExisting_answersBothCompositeIdsInInputOrder() {
        // Pair (2, 5) is seeded here; (5, 6) is not in the init.sql seed.
        inRolledBackTransaction(tx -> {
            seedFilmActor(tx, 2, 5);
            String missing = NodeIdEncoder.encode("FilmActor", 5, 6);
            String existing = NodeIdEncoder.encode("FilmActor", 2, 5);

            Map<String, Object> data = execute(tx, """
                mutation { deleteFilmActorsByNodeId(in: [{ id: "%s" }, { id: "%s" }]) }
                """.formatted(missing, existing));

            assertThat((List<String>) data.get("deleteFilmActorsByNodeId")).containsExactly(missing, existing);
            assertThat(countFilmActor(tx, 2, 5)).isZero();
        });
    }

    @Test
    void createKeyedNode_singlePkNodeIdDecodedInsert_writesRow() {
        // Single-PK INSERT keyed by a client-supplied NodeId. Drives
        // buildInsertDecodeLocals (one preGuard decode local per NodeId-bearing
        // carrier) plus the INSERT cell's decode-value read
        // (`insertKey_<fi>.value1()` put as the column's cell). Classifies as
        // ColumnField with NodeIdDecodeKeys extraction.
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
        // Uses pair (3, 3), not in the init.sql seed.
        inRolledBackTransaction(tx -> {
            seedFilmActor(tx, 3, 3);
            String wrong = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
            graphql.ExecutionResult result = executeRaw(tx, """
                mutation {
                    deleteFilmActorByNodeId(in: { id: "%s" })
                }
                """.formatted(wrong));
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(countFilmActor(tx, 3, 3)).isEqualTo(1);
        });
    }

    // ===== multiRow DELETE broadcast =====

    @Test
    void deleteFilmsByReleaseYear_multiRowBroadcastsAcrossInputCardinality() {
        // End-to-end proof of the multiRow opt-out. The mutation declares
        // `@mutation(typeName: DELETE, multiRow: true)` and filters on release_year
        // (non-PK). With three pre-inserted rows sharing one release_year, a single
        // input row deletes all three; |affected rows| > |input rows| is the
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
    // read against the gone row. (There is no @table-element DELETE carrier: a full @table
    // projection off a deleted row is impossible.)

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

    // ===== the zero-row guard, logged through a FetcherErrorLog =====
    //
    // A bulk row that matches nothing fails the whole list: the zero-row guard throws, the payload
    // has no error channel, so the throw is unmatched and redacted. The escape-hatch engine has no
    // runtime, so the log rides in the request's GraphQLContext under its class; graphql-java's
    // graphQLContext(Consumer) merges into the entries the generated factory already wrote. The
    // second row's title is the sentinel: the guard's message names the row by index and the field
    // by coordinate, so the client's input never reaches the log. Each case seeds and runs inside
    // one rolled-back transaction, so no reader in another class sees the seeded film.

    /** One {@link FetcherErrorLog#unmatched} call. */
    private record LoggedUnmatched(Throwable thrown, String reference) {}

    private graphql.ExecutionResult runWithLog(DSLContext ctx, String query, FetcherErrorLog log) {
        var input = Graphitron.newExecutionInput(ctx, "{}", "test-user")
            .query(query)
            .graphQLContext(b -> b.put(FetcherErrorLog.class, log))
            .build();
        return graphql.execute(input);
    }

    private static Integer insertFilm(DSLContext tx, String title) {
        return tx.insertInto(DSL.table("film"))
            .set(DSL.field("title"), title)
            .set(DSL.field("language_id"), (short) 1)
            .returningResult(DSL.field("film_id", Integer.class))
            .fetchOne().value1();
    }

    private static String referenceOf(graphql.ExecutionResult result) {
        assertThat(result.getErrors()).hasSize(1);
        String message = result.getErrors().get(0).getMessage();
        assertThat(message).matches("An error occurred\\. Reference: [0-9a-f-]{36}\\.");
        return message.substring("An error occurred. Reference: ".length(), message.length() - 1);
    }

    @Test
    void updateFilmsPayload_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow() {
        inRolledBackTransaction(tx -> {
            String marker = randomMarker("R1048-UPDATE");
            Integer existing = insertFilm(tx, marker);
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    updateFilmsPayload(in: [
                        { filmId: %d, title: "%s" },
                        { filmId: 2147483000, title: "sentinel-title-7f3a" }
                    ]) { films { filmId } }
                }
                """.formatted(existing, marker + "-renamed"),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            String reference = referenceOf(result);
            assertThat(logged).hasSize(1);
            assertThat(logged.get(0).reference()).isEqualTo(reference);
            assertThat(logged.get(0).thrown()).isInstanceOf(IllegalStateException.class);
            assertThat(logged.get(0).thrown().getMessage())
                .contains("matched zero rows")
                .contains("at index 1")
                .contains("Mutation.updateFilmsPayload")
                .doesNotContain("sentinel-title-7f3a");
            assertThat(tx.fetchValue(DSL.select(DSL.field("title", String.class)).from(DSL.table("film"))
                    .where(DSL.field("film_id", Integer.class).eq(existing))))
                .as("the failed row rolls the whole list back")
                .isEqualTo(marker);
        });
    }

    // The payload bulk UPDATE runs every row before failing, so the generated exception counts
    // every miss. The sentinel ids name no film; the sentinel titles must not reach the message.

    @Test
    void updateFilmsPayload_twoMissesOfFive_countsEveryMissAndRollsBack() {
        inRolledBackTransaction(tx -> {
            String marker = randomMarker("zero-rows-count");
            Integer first = insertFilm(tx, marker + "-a");
            Integer second = insertFilm(tx, marker + "-b");
            Integer third = insertFilm(tx, marker + "-c");
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    updateFilmsPayload(in: [
                        { filmId: %d, title: "renamed-a" },
                        { filmId: 2147483001, title: "sentinel-title-1" },
                        { filmId: %d, title: "renamed-b" },
                        { filmId: 2147483002, title: "sentinel-title-3" },
                        { filmId: %d, title: "renamed-c" }
                    ]) { films { filmId } }
                }
                """.formatted(first, second, third),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            assertThat(logged.get(0).reference()).isEqualTo(referenceOf(result));
            assertThat(logged).singleElement().extracting(LoggedUnmatched::thrown)
                .isInstanceOfSatisfying(ZeroRowsMatchedException.class, miss -> {
                    assertThat(miss.missCount()).isEqualTo(2);
                    assertThat(miss.rowCount()).isEqualTo(5);
                    assertThat(miss.missedRowIndices()).containsExactly(1, 3);
                    assertThat(miss.fieldCoordinate()).isEqualTo("Mutation.updateFilmsPayload");
                    assertThat(miss.getMessage())
                        .contains("matched zero rows")
                        .contains("2 of 5")
                        .contains("Mutation.updateFilmsPayload")
                        .doesNotContain("sentinel-title");
                });
            assertThat(titlesOf(tx, first, second, third))
                .as("the rows that matched, before and after each miss, roll back with the call")
                .containsExactly(marker + "-a", marker + "-b", marker + "-c");
        });
    }

    @Test
    void updateFilmsPayload_missOnTheLastRow_runsTheEarlierRowsAndRollsThemBack() {
        inRolledBackTransaction(tx -> {
            String marker = randomMarker("zero-rows-last");
            Integer first = insertFilm(tx, marker + "-a");
            Integer second = insertFilm(tx, marker + "-b");
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    updateFilmsPayload(in: [
                        { filmId: %d, title: "renamed-a" },
                        { filmId: %d, title: "renamed-b" },
                        { filmId: 2147483001, title: "sentinel-title-2" }
                    ]) { films { filmId } }
                }
                """.formatted(first, second),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            assertThat(result.getErrors()).hasSize(1);
            assertThat(logged).singleElement().extracting(LoggedUnmatched::thrown)
                .isInstanceOfSatisfying(ZeroRowsMatchedException.class, miss -> {
                    assertThat(miss.missedRowIndices()).containsExactly(2);
                    assertThat(miss.rowCount()).isEqualTo(3);
                    assertThat(miss.getMessage()).contains("1 of 3").contains("first at index 2");
                });
            assertThat(titlesOf(tx, first, second)).containsExactly(marker + "-a", marker + "-b");
        });
    }

    private static List<String> titlesOf(DSLContext tx, Integer... filmIds) {
        return java.util.Arrays.stream(filmIds)
            .map(id -> tx.fetchValue(DSL.select(DSL.field("title", String.class)).from(DSL.table("film"))
                .where(DSL.field("film_id", Integer.class).eq(id))))
            .toList();
    }

    @Test
    void deleteFilmsFailOnMissing_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow() {
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("R1048-DELETE"));
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    deleteFilmsFailOnMissing(in: [
                        { filmId: %d },
                        { filmId: 2147483000 }
                    ]) { deletedIds }
                }
                """.formatted(existing),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            String reference = referenceOf(result);
            assertThat(logged).hasSize(1);
            assertThat(logged.get(0).reference()).isEqualTo(reference);
            assertThat(logged.get(0).thrown().getMessage())
                .contains("matched zero rows")
                .contains("at index 1")
                .contains("Mutation.deleteFilmsFailOnMissing")
                .doesNotContain("2147483000");
            assertThat(countFilm(tx, existing)).as("the existing row's delete rolls back").isEqualTo(1);
        });
    }

    @Test
    void deleteFilmFailOnMissing_singleMissingKey_reportsAnErrorRatherThanNull() {
        inRolledBackTransaction(tx -> {
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    deleteFilmFailOnMissing(in: { filmId: %d }) { deletedId }
                }
                """.formatted(MISSING_FILM_ID),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            assertThat(result.getErrors()).hasSize(1);
            assertThat(logged).singleElement().satisfies(l -> assertThat(l.thrown().getMessage())
                .contains("matched zero rows")
                .contains("Mutation.deleteFilmFailOnMissing")
                .doesNotContain(String.valueOf(MISSING_FILM_ID)));
        });
    }

    // ===== the default reading: a key that matches no row counts as already deleted =====
    //
    // Each input key that matches no row is echoed in its payload slot, encoded the way a deleted
    // row's key is, and the call commits. 2147483000 is a film id no fixture reaches.

    private static final int MISSING_FILM_ID = 2147483000;

    private static int countFilm(DSLContext ctx, int filmId) {
        return ctx.fetchCount(DSL.selectOne().from(DSL.table("film"))
            .where(DSL.field("film_id", Integer.class).eq(filmId)));
    }

    private static String filmId(int filmId) {
        return NodeIdEncoder.encode("Film", filmId);
    }

    private static List<String> deletedIds(Map<String, Object> data, String field) {
        return (List<String>) ((Map<String, Object>) data.get(field)).get("deletedIds");
    }

    @Test
    void deleteFilmsIdCarrier_existingThenMissing_deletesTheExistingAndEchoesBoth() {
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("idempotent-delete"));
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    deleteFilmsIdCarrier(in: [{ filmId: %d }, { filmId: %d }]) { deletedIds }
                }
                """.formatted(existing, MISSING_FILM_ID),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            assertThat(result.getErrors()).isEmpty();
            Map<String, Object> data = result.getData();
            assertThat(deletedIds(data, "deleteFilmsIdCarrier"))
                .containsExactly(filmId(existing), filmId(MISSING_FILM_ID));
            assertThat(NodeIdEncoder.decodeValues("Film", deletedIds(data, "deleteFilmsIdCarrier").get(1)))
                .containsExactly(String.valueOf(MISSING_FILM_ID));
            assertThat(countFilm(tx, existing)).isZero();
            assertThat(logged).isEmpty();
        });
    }

    @Test
    void deleteFilmsIdCarrier_missingThenExisting_keepsInputOrder() {
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("idempotent-delete"));

            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmsIdCarrier(in: [{ filmId: %d }, { filmId: %d }]) { deletedIds }
                }
                """.formatted(MISSING_FILM_ID, existing));

            assertThat(deletedIds(data, "deleteFilmsIdCarrier"))
                .containsExactly(filmId(MISSING_FILM_ID), filmId(existing));
            assertThat(countFilm(tx, existing)).isZero();
        });
    }

    @Test
    void deleteFilmsIdCarrier_duplicateKey_echoesBothSlots() {
        // The second row's delete matches nothing, since the first already removed the film.
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("idempotent-delete"));

            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmsIdCarrier(in: [{ filmId: %d }, { filmId: %d }]) { deletedIds }
                }
                """.formatted(existing, existing));

            assertThat(deletedIds(data, "deleteFilmsIdCarrier"))
                .containsExactly(filmId(existing), filmId(existing));
            assertThat(countFilm(tx, existing)).isZero();
        });
    }

    @Test
    void deleteFilmsIdCarrier_retriedBatch_answersTheSameWithoutError() {
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("idempotent-delete"));
            String query = """
                mutation {
                    deleteFilmsIdCarrier(in: [{ filmId: %d }, { filmId: %d }]) { deletedIds }
                }
                """.formatted(existing, MISSING_FILM_ID);

            Map<String, Object> first = execute(tx, query);
            Map<String, Object> second = execute(tx, query);

            assertThat(deletedIds(second, "deleteFilmsIdCarrier"))
                .isEqualTo(deletedIds(first, "deleteFilmsIdCarrier"))
                .containsExactly(filmId(existing), filmId(MISSING_FILM_ID));
            assertThat(countFilm(tx, existing)).isZero();
        });
    }

    @Test
    void deleteFilmIdCarrier_singleMissingKey_echoesTheInputRatherThanNull() {
        inRolledBackTransaction(tx -> {
            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmIdCarrier(in: { filmId: %d }) { deletedId }
                }
                """.formatted(MISSING_FILM_ID));

            var payload = (Map<String, Object>) data.get("deleteFilmIdCarrier");
            assertThat(payload).isNotNull();
            assertThat(payload.get("deletedId")).isEqualTo(filmId(MISSING_FILM_ID));
        });
    }

    @Test
    void deleteFilmIdCarrier_singleExistingKey_deletesAndEchoes() {
        inRolledBackTransaction(tx -> {
            Integer existing = insertFilm(tx, randomMarker("idempotent-delete"));

            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmIdCarrier(in: { filmId: %d }) { deletedId }
                }
                """.formatted(existing));

            var payload = (Map<String, Object>) data.get("deleteFilmIdCarrier");
            assertThat(payload.get("deletedId")).isEqualTo(filmId(existing));
            assertThat(countFilm(tx, existing)).isZero();
        });
    }

    @Test
    void deleteFilmActorsWithErrors_compositeNodeId_echoesTheMissingKeyThroughTheOutcomePath() {
        // film_actor's key is (actor_id, film_id), decoded from one node id; the payload declares an
        // errors field, so the fetcher's value is Outcome-wrapped. Pairs (4, 5) and (5, 6) are not in
        // the init.sql seed; only (4, 5) is seeded here.
        inRolledBackTransaction(tx -> {
            seedFilmActor(tx, 4, 5);
            String existing = NodeIdEncoder.encode("FilmActor", 4, 5);
            String missing = NodeIdEncoder.encode("FilmActor", 5, 6);

            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmActorsWithErrors(in: [{ id: "%s" }, { id: "%s" }]) {
                        deletedIds
                        errors { __typename }
                    }
                }
                """.formatted(existing, missing));

            var payload = (Map<String, Object>) data.get("deleteFilmActorsWithErrors");
            assertThat((List<String>) payload.get("deletedIds")).containsExactly(existing, missing);
            assertThat(NodeIdEncoder.decodeValues("FilmActor", ((List<String>) payload.get("deletedIds")).get(1)))
                .containsExactly("5", "6");
            assertThat(payload.get("errors")).isNull();
            assertThat(countFilmActor(tx, 4, 5)).isZero();
        });
    }

    @Test
    void deleteSplitKeyChildren_interleavedKey_echoesEachKeyInItsOwnSlots() {
        // split_key_child's key is (a, b, c); the input binds a and c from a SplitKeyParent node id
        // and b from a plain field, so the WHERE compares a, c, b. Every value is distinct, so an
        // echo that zipped the values in WHERE order would decode to (1, 3, 2) / (7, 9, 8).
        inRolledBackTransaction(tx -> {
            tx.insertInto(DSL.table("split_key_parent"))
                .set(DSL.field("a"), 1).set(DSL.field("c"), 3).execute();
            tx.insertInto(DSL.table("split_key_child"))
                .set(DSL.field("a"), 1).set(DSL.field("b"), 2).set(DSL.field("c"), 3).execute();

            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteSplitKeyChildren(in: [
                        { parent: "%s", b: 2 },
                        { parent: "%s", b: 8 }
                    ]) { deletedIds }
                }
                """.formatted(NodeIdEncoder.encode("SplitKeyParent", 1, 3),
                    NodeIdEncoder.encode("SplitKeyParent", 7, 9)));

            var ids = deletedIds(data, "deleteSplitKeyChildren");
            assertThat(ids).hasSize(2);
            assertThat(NodeIdEncoder.decodeValues("SplitKeyChild", ids.get(0))).containsExactly("1", "2", "3");
            assertThat(NodeIdEncoder.decodeValues("SplitKeyChild", ids.get(1))).containsExactly("7", "8", "9");
            assertThat(tx.fetchCount(DSL.selectOne().from(DSL.table("split_key_child")))).isZero();
        });
    }

    // ===== Superkey node ids: the primary key plus more columns =====
    //
    // InventoryUnit's node key is (inventory_id, store_id) over inventory's primary key
    // inventory_id; FilmCategoryNode's is (last_update, film_id, category_id) over film_category's
    // (film_id, category_id), opening with a column outside the key so its encoder's parameter
    // order differs from the order RETURNING captures in. An UPDATE or DELETE taking such an id filters on the primary key the
    // id carries and ignores the rest, so an id minted before the extra column changed still names
    // its row. Reads keep filtering on every column.

    private static final LocalDateTime MINTED_AT = LocalDateTime.of(2020, 1, 1, 12, 0);
    private static final LocalDateTime STORED_AT = LocalDateTime.of(2021, 6, 15, 8, 30);

    // inventory's serial lags the seed's explicit ids, so these rows take ids of their own.
    private static final AtomicInteger NEXT_INVENTORY_ID = new AtomicInteger(1_900_000_000);

    private static int insertInventory(DSLContext tx, int filmId, int storeId) {
        return tx.insertInto(DSL.table("inventory"))
            .set(DSL.field("inventory_id"), NEXT_INVENTORY_ID.incrementAndGet())
            .set(DSL.field("film_id"), filmId)
            .set(DSL.field("store_id"), storeId)
            .returningResult(DSL.field("inventory_id", Integer.class))
            .fetchOne().value1();
    }

    private static Integer storeOf(DSLContext tx, int inventoryId) {
        return tx.select(DSL.field("store_id", Integer.class)).from(DSL.table("inventory"))
            .where(DSL.field("inventory_id", Integer.class).eq(inventoryId))
            .fetchOne(0, Integer.class);
    }

    private static void moveInventory(DSLContext tx, int inventoryId, int storeId) {
        tx.update(DSL.table("inventory")).set(DSL.field("store_id"), storeId)
            .where(DSL.field("inventory_id", Integer.class).eq(inventoryId)).execute();
    }

    private static String inventoryUnitId(int inventoryId, int storeId) {
        return NodeIdEncoder.encodeInventoryUnit(inventoryId, storeId);
    }

    @Test
    void updateInventoryUnit_byAnIdMintedBeforeAStoreChange_updatesTheRowAndReturnsTheNewStore() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx, insertFilm(tx, randomMarker("superkey")), 1);
            String minted = inventoryUnitId(inventoryId, 1);

            Map<String, Object> moved = execute(tx, """
                mutation { updateInventoryUnit(in: { id: "%s", storeId: 2 }) { inventoryUnit { id storeId } } }
                """.formatted(minted));
            var unit = (Map<String, Object>) ((Map<String, Object>) moved.get("updateInventoryUnit")).get("inventoryUnit");
            assertThat(unit.get("id")).isEqualTo(inventoryUnitId(inventoryId, 2));
            assertThat(unit.get("storeId")).isEqualTo(2);

            // The minted id now encodes a store the row is no longer in; it still names the row.
            Map<String, Object> back = execute(tx, """
                mutation { updateInventoryUnit(in: { id: "%s", storeId: 1 }) { inventoryUnit { id } } }
                """.formatted(minted));
            var backUnit = (Map<String, Object>) ((Map<String, Object>) back.get("updateInventoryUnit")).get("inventoryUnit");
            assertThat(backUnit.get("id")).isEqualTo(minted);
            assertThat(storeOf(tx, inventoryId)).isEqualTo(1);
        });
    }

    @Test
    void updateInventoryUnits_bulkByStaleIds_updatesEveryRowAndReturnsTheNewStores() {
        inRolledBackTransaction(tx -> {
            int filmId = insertFilm(tx, randomMarker("superkey"));
            int first = insertInventory(tx, filmId, 1);
            int second = insertInventory(tx, filmId, 1);
            String firstMinted = inventoryUnitId(first, 1);
            String secondMinted = inventoryUnitId(second, 1);
            moveInventory(tx, first, 2);
            moveInventory(tx, second, 2);

            Map<String, Object> data = execute(tx, """
                mutation {
                    updateInventoryUnits(in: [{ id: "%s", storeId: 1 }, { id: "%s", storeId: 2 }]) {
                        inventoryUnits { id }
                    }
                }
                """.formatted(secondMinted, firstMinted));

            var units = (List<Map<String, Object>>) ((Map<String, Object>) data.get("updateInventoryUnits")).get("inventoryUnits");
            assertThat(units).extracting(u -> u.get("id"))
                .containsExactly(inventoryUnitId(second, 1), inventoryUnitId(first, 2));
            assertThat(storeOf(tx, second)).isEqualTo(1);
            assertThat(storeOf(tx, first)).isEqualTo(2);
        });
    }

    @Test
    void updateInventoryUnitsDirect_twoIdsDifferingOnlyInStore_failOnTheDuplicateKeyGuardAndWriteNothing() {
        // Both ids name one row by its primary key. The VALUES join would hand that row two input
        // rows and silently lose one, so the guard compares the key the WHERE joins on.
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx, insertFilm(tx, randomMarker("superkey")), 1);
            var logged = new CopyOnWriteArrayList<LoggedUnmatched>();

            var result = runWithLog(tx, """
                mutation {
                    updateInventoryUnitsDirect(in: [{ id: "%s", storeId: 2 }, { id: "%s", storeId: 2 }]) { id }
                }
                """.formatted(inventoryUnitId(inventoryId, 1), inventoryUnitId(inventoryId, 2)),
                (thrown, reference, env) -> logged.add(new LoggedUnmatched(thrown, reference)));

            assertThat(result.getErrors()).isNotEmpty();
            assertThat(logged).singleElement()
                .satisfies(l -> assertThat(l.thrown().getMessage()).contains("duplicate"));
            assertThat(storeOf(tx, inventoryId)).isEqualTo(1);
        });
    }

    @Test
    void deleteInventoryUnit_byAStaleId_deletesTheRowAndAnswersTheStoredStore() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx, insertFilm(tx, randomMarker("superkey")), 1);
            String minted = inventoryUnitId(inventoryId, 1);
            moveInventory(tx, inventoryId, 2);

            Map<String, Object> data = execute(tx, """
                mutation { deleteInventoryUnit(in: { id: "%s" }) { deletedId } }
                """.formatted(minted));

            var payload = (Map<String, Object>) data.get("deleteInventoryUnit");
            assertThat(payload.get("deletedId")).isEqualTo(inventoryUnitId(inventoryId, 2));
            assertThat(storeOf(tx, inventoryId)).isNull();
        });
    }

    @Test
    void node_byAStaleInventoryUnitId_resolvesToNull() {
        inRolledBackTransaction(tx -> {
            int inventoryId = insertInventory(tx, insertFilm(tx, randomMarker("superkey")), 1);
            String minted = inventoryUnitId(inventoryId, 1);
            moveInventory(tx, inventoryId, 2);

            Map<String, Object> stale = execute(tx, """
                { node(id: "%s") { id } }
                """.formatted(minted));
            assertThat(stale.get("node")).isNull();

            Map<String, Object> current = execute(tx, """
                { node(id: "%s") { id } }
                """.formatted(inventoryUnitId(inventoryId, 2)));
            assertThat(((Map<String, Object>) current.get("node")).get("id")).isEqualTo(inventoryUnitId(inventoryId, 2));
        });
    }

    private static void insertFilmCategory(DSLContext tx, int filmId, int categoryId, LocalDateTime lastUpdate) {
        tx.insertInto(DSL.table("film_category"))
            .set(DSL.field("film_id"), filmId)
            .set(DSL.field("category_id"), categoryId)
            .set(DSL.field("last_update"), lastUpdate)
            .execute();
    }

    private static void touchFilmCategories(DSLContext tx, int filmId, LocalDateTime lastUpdate) {
        tx.update(DSL.table("film_category")).set(DSL.field("last_update"), lastUpdate)
            .where(DSL.field("film_id", Integer.class).eq(filmId)).execute();
    }

    private static int countFilmCategories(DSLContext tx, int filmId) {
        return tx.fetchCount(DSL.selectOne().from(DSL.table("film_category"))
            .where(DSL.field("film_id", Integer.class).eq(filmId)));
    }

    private static String filmCategoryNodeId(int filmId, int categoryId, LocalDateTime lastUpdate) {
        return NodeIdEncoder.encodeFilmCategoryNode(lastUpdate, filmId, categoryId);
    }

    @Test
    void deleteFilmCategoryNodes_byStaleIds_deletesTheRowsAndAnswersTheStoredLastUpdate() {
        inRolledBackTransaction(tx -> {
            int filmId = insertFilm(tx, randomMarker("superkey"));
            insertFilmCategory(tx, filmId, 1, MINTED_AT);
            insertFilmCategory(tx, filmId, 2, MINTED_AT);
            touchFilmCategories(tx, filmId, STORED_AT);

            Map<String, Object> data = execute(tx, """
                mutation { deleteFilmCategoryNodes(in: [{ id: "%s" }, { id: "%s" }]) { deletedIds } }
                """.formatted(filmCategoryNodeId(filmId, 2, MINTED_AT), filmCategoryNodeId(filmId, 1, MINTED_AT)));

            assertThat(deletedIds(data, "deleteFilmCategoryNodes")).containsExactly(
                filmCategoryNodeId(filmId, 2, STORED_AT), filmCategoryNodeId(filmId, 1, STORED_AT));
            assertThat(countFilmCategories(tx, filmId)).isZero();
        });
    }

    @Test
    void deleteFilmCategoryNodes_missingThenStale_echoesTheInputIdAndKeepsInputOrder() {
        inRolledBackTransaction(tx -> {
            int filmId = insertFilm(tx, randomMarker("superkey"));
            insertFilmCategory(tx, filmId, 1, MINTED_AT);
            touchFilmCategories(tx, filmId, STORED_AT);
            String missing = filmCategoryNodeId(filmId, 3, MINTED_AT);

            Map<String, Object> data = execute(tx, """
                mutation {
                    deleteFilmCategoryNodes(in: [{ id: "%s" }, { id: "%s" }]) { deletedIds }
                }
                """.formatted(missing, filmCategoryNodeId(filmId, 1, MINTED_AT)));

            assertThat(deletedIds(data, "deleteFilmCategoryNodes"))
                .containsExactly(missing, filmCategoryNodeId(filmId, 1, STORED_AT));
            assertThat(countFilmCategories(tx, filmId)).isZero();
        });
    }

    @Test
    void deleteFilmCategoryNodesDirect_missingThenStale_answersTheSentIdAndTheStoredLastUpdate() {
        // The direct [ID!] DELETE over a node whose key is not in primary-key-then-tail order: the
        // missing id is answered as sent, the deleted one with the stored last_update.
        inRolledBackTransaction(tx -> {
            int filmId = insertFilm(tx, randomMarker("superkey"));
            insertFilmCategory(tx, filmId, 1, MINTED_AT);
            touchFilmCategories(tx, filmId, STORED_AT);
            String missing = filmCategoryNodeId(filmId, 3, MINTED_AT);

            Map<String, Object> data = execute(tx, """
                mutation { deleteFilmCategoryNodesDirect(in: [{ id: "%s" }, { id: "%s" }]) }
                """.formatted(missing, filmCategoryNodeId(filmId, 1, MINTED_AT)));

            assertThat((List<String>) data.get("deleteFilmCategoryNodesDirect"))
                .containsExactly(missing, filmCategoryNodeId(filmId, 1, STORED_AT));
            assertThat(countFilmCategories(tx, filmId)).isZero();
        });
    }

    // ===== UK-covering single-row DELETE =====
    //
    // storage_bin is the one public-schema table with a UNIQUE constraint (`code`) distinct from
    // its `bin_id` PK. deleteStorageBinByCode covers `code` only, so the DeleteRowsWalker's
    // PK-or-UK match lands on the UniqueKey arm rather than the PK: the UK-covering single-row
    // delete every other Sakila DELETE fixture (all PK-keyed) leaves unproven at execution tier.
    // The emitted statement is `DELETE FROM storage_bin WHERE code = ? RETURNING bin_id`, and the
    // `: ID` return encodes the matched bin_id as a StorageBin NodeId. A unique key cannot answer a
    // code that matches no bin, so both fields set failOnMissing: true and a miss fails the call.

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
        // code must survive: proof the WHERE keys on the UK value, not a blanket delete.
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
    void deleteStorageBinByCode_codeMatchingNoBin_fails() {
        graphql.ExecutionResult result = executeRaw("""
            mutation { deleteStorageBinByCode(in: { code: "%s" }) }
            """.formatted(randomMarker("R266-UK-MISSING")));
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void deleteStorageBinByCodePayload_codeMatchingNoBin_fails() {
        graphql.ExecutionResult result = executeRaw("""
            mutation { deleteStorageBinByCodePayload(in: { code: "%s" }) { deletedId } }
            """.formatted(randomMarker("R514-payload-MISSING")));
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(((Map<String, Object>) result.getData()).get("deleteStorageBinByCodePayload")).isNull();
    }

    @Test
    void deleteStorageBinByCodePayload_fieldTableWriteTarget_roundTrips() {
        // The payload-returning sibling of deleteStorageBinByCode. The write target is named on
        // the field (@mutation(table: "storage_bin")); the input carries no @table, so the
        // DmlEmitted grounding comes from the field's @mutation(table:). The field must classify,
        // delete the UK-matched row, and echo its bin_id as a StorageBin NodeId inside the
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
