package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.ConnectionHelper;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.CONVERTER_ID_CHANGE;
import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM_ACTOR;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection cursor's wire format: a JSON array with one element per order column, every
 * non-null value a JSON string of the column's database value, encoded as URL-safe Base64 without
 * padding. That is the format Graphitron 9 issued, so a cursor a client stored under Graphitron 9
 * keeps its place after the upgrade on a connection whose order already identifies a row.
 *
 * <p>A Graphitron 9 cursor is built here the way Graphitron 9 built it, in SQL: {@code jsonArray}
 * over the order columns for one seeded row, in the database's own JSON rendering (bare numbers, a
 * {@code timestamptz} with its offset), then encoded. Films another class inserts get higher ids
 * than the five seeded ones, so a page after a seeded film by {@code film_id} is the seeded films
 * that follow it; a title-ordered page picks the seeded rows out by id.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class CursorFormatExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();
        graphql = Graphitron.newGraphQL().build();
    }

    private graphql.ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    private Map<String, Object> execute(String query) {
        var result = executeRaw(query);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    /** The cursor Graphitron 9 issued for the row {@code condition} selects: its order columns' {@code jsonArray}, in SQL. */
    private static String graphitron9Cursor(org.jooq.Table<?> table, org.jooq.Condition condition, Field<?>... orderColumns) {
        String json = dsl.select(DSL.jsonArray(orderColumns)).from(table).where(condition).fetchSingle().value1().data();
        return encode(json);
    }

    private static List<Object> column(Map<String, Object> data, String field, String key) {
        var nodes = (List<Map<String, Object>>) ((Map<String, Object>) data.get(field)).get("nodes");
        return nodes.stream().map(n -> n.get(key)).toList();
    }

    private static Object bound(Field<?> field) {
        return ((Param<?>) field).getValue();
    }

    // ===== A Graphitron 9 cursor keeps its place =====

    @Test
    void graphitron9Cursor_overOneNumericColumn_pagesToTheRowsAfterIt() {
        String cursor = graphitron9Cursor(FILM, FILM.FILM_ID.eq(2), FILM.FILM_ID);
        assertThat(cursor).as("the encoding of [2]").isEqualTo("WzJd");
        var data = execute("{ filmsConnection(first: 2, after: \"" + cursor + "\") "
            + "{ nodes { title } pageInfo { endCursor } } }");
        assertThat(column(data, "filmsConnection", "title")).containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
        var pageInfo = (Map<String, Object>) ((Map<String, Object>) data.get("filmsConnection")).get("pageInfo");
        assertThat(pageInfo).as("the encoding of [\"4\"]").containsEntry("endCursor", "WyI0Il0");
    }

    @Test
    void graphitron9Cursor_overATextAndANumericColumn_pagesToTheRowsAfterIt() {
        // TITLE then FILM_ID covers the primary key, so graphitron appends nothing and pages on
        // exactly the columns Graphitron 9 paged on.
        String cursor = graphitron9Cursor(FILM, FILM.FILM_ID.eq(2), FILM.TITLE, FILM.FILM_ID);
        assertThat(decode(cursor)).isEqualTo("[\"ACE GOLDFINGER\", 2]");
        var data = execute("{ filmsOrderedConnection(order: [{field: TITLE}, {field: FILM_ID}], first: 100, "
            + "after: \"" + cursor + "\") { nodes { filmId title } } }");
        var nodes = (List<Map<String, Object>>) ((Map<String, Object>) data.get("filmsOrderedConnection")).get("nodes");
        assertThat(nodes.stream().filter(n -> (Integer) n.get("filmId") <= 5).map(n -> n.get("title")).toList())
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE", "AGENT TRUMAN");
    }

    @Test
    void graphitron9Cursor_asBefore_pagesToTheRowsBeforeIt() {
        String cursor = graphitron9Cursor(FILM, FILM.FILM_ID.eq(4), FILM.FILM_ID);
        var data = execute("{ filmsConnection(last: 2, before: \"" + cursor + "\") { nodes { title } } }");
        assertThat(column(data, "filmsConnection", "title")).containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    @Test
    void graphitron9Cursor_overANumericCompositeKey_pagesToTheRowsAfterIt() {
        // film_actor's primary key (actor_id, film_id), the feed shape: numeric columns that
        // together identify a row. PostgreSQL renders the array with a space after the comma.
        String cursor = graphitron9Cursor(FILM_ACTOR, FILM_ACTOR.ACTOR_ID.eq(1).and(FILM_ACTOR.FILM_ID.eq(2)),
            FILM_ACTOR.ACTOR_ID, FILM_ACTOR.FILM_ID);
        assertThat(decode(cursor)).isEqualTo("[1, 2]");
        var data = execute("{ filmActorsFaceted(first: 2, after: \"" + cursor + "\") { nodes { actorId filmId } } }");
        var nodes = (List<Map<String, Object>>) ((Map<String, Object>) data.get("filmActorsFaceted")).get("nodes");
        assertThat(nodes).extracting(n -> List.of(n.get("actorId"), n.get("filmId")))
            .containsExactly(List.of(1, 3), List.of(2, 1));
    }

    @Test
    void graphitron9Cursor_onAnOrderGraphitronCompletes_isRejected() {
        // Rental rate and title do not identify a film, so graphitron pages on rental_rate DESC,
        // title ASC, film_id ASC, and the two-element cursor Graphitron 9 issued fails arity. A
        // change that starts accepting it should break this on purpose.
        String cursor = graphitron9Cursor(FILM, FILM.FILM_ID.eq(3), FILM.RENTAL_RATE, FILM.TITLE);
        var result = executeRaw("{ filmsByRateDescTitleAsc(first: 2, after: \"" + cursor + "\") { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .isEqualTo("cursor is not valid (was: \"" + cursor + "\")");
    }

    @Test
    void graphitron9Timestamp_inOraclesRendering_bindsTheLocalDateTime() {
        // Oracle renders a DATE in JSON as a bare ISO string with no offset and no fraction.
        Field<?>[] seek = ConnectionHelper.decodeCursor(encode("[\"2024-01-01T00:00:00\"]"), List.of(FILM.LAST_UPDATE));
        assertThat(bound(seek[0])).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
        seek = ConnectionHelper.decodeCursor(encode("[\"2024-01-01T10:11:12.123456\"]"), List.of(FILM.LAST_UPDATE));
        assertThat(bound(seek[0])).isEqualTo(LocalDateTime.of(2024, 1, 1, 10, 11, 12, 123_456_000));
    }

    // ===== A converter-backed order column =====
    // converter_id_change's key is (person_id, changed_at), changed_at a timestamptz the fixture's
    // UtcLocalDateTimeConverter maps to LocalDateTime. The cursor carries the database value, which
    // the column's database type parses; the LocalDateTime text it would otherwise carry does not.

    @Test
    void converterColumn_forward_pagesFromTheMidnightRowToTheSmallHoursRow() {
        var page1 = execute("{ converterIdChangesConnection(first: 1) { nodes { newValue } pageInfo { endCursor } } }");
        assertThat(column(page1, "converterIdChangesConnection", "newValue")).containsExactly("midnight");
        String endCursor = (String) ((Map<String, Object>) ((Map<String, Object>)
            page1.get("converterIdChangesConnection")).get("pageInfo")).get("endCursor");
        assertThat(decode(endCursor)).as("the database value, an OffsetDateTime at UTC")
            .isEqualTo("[\"1\",\"2007-08-22T00:00Z\"]");
        var page2 = execute("{ converterIdChangesConnection(first: 1, after: \"" + endCursor + "\") { nodes { newValue } } }");
        assertThat(column(page2, "converterIdChangesConnection", "newValue")).containsExactly("small hours");
    }

    @Test
    void converterColumn_backward_pagesFromTheSmallHoursRowToTheMidnightRow() {
        var page = execute("{ converterIdChangesConnection(first: 2) { edges { cursor node { newValue } } } }");
        var edges = (List<Map<String, Object>>) ((Map<String, Object>) page.get("converterIdChangesConnection")).get("edges");
        assertThat(edges).extracting(e -> ((Map<String, Object>) e.get("node")).get("newValue"))
            .containsExactly("midnight", "small hours");
        String smallHours = (String) edges.get(1).get("cursor");
        var before = execute("{ converterIdChangesConnection(last: 1, before: \"" + smallHours + "\") { nodes { newValue } } }");
        assertThat(column(before, "converterIdChangesConnection", "newValue")).containsExactly("midnight");
    }

    @Test
    void converterColumn_graphitron9Cursor_pagesToTheSmallHoursRow() {
        String cursor = graphitron9Cursor(CONVERTER_ID_CHANGE, CONVERTER_ID_CHANGE.NEW_VALUE.eq("midnight"),
            CONVERTER_ID_CHANGE.PERSON_ID, CONVERTER_ID_CHANGE.CHANGED_AT);
        assertThat(decode(cursor)).as("PostgreSQL's rendering, with its offset").startsWith("[1, \"2007-08-22T");
        var data = execute("{ converterIdChangesConnection(first: 1, after: \"" + cursor + "\") { nodes { newValue } } }");
        assertThat(column(data, "converterIdChangesConnection", "newValue")).containsExactly("small hours");
    }

    // ===== The emitted format =====

    @Test
    void endCursor_isAUrlSafeUnpaddedJsonArrayOfStrings() {
        var data = execute("{ filmsOrderedConnection(order: [{field: TITLE}, {field: FILM_ID}], first: 1) "
            + "{ pageInfo { endCursor } } }");
        String endCursor = (String) ((Map<String, Object>) ((Map<String, Object>)
            data.get("filmsOrderedConnection")).get("pageInfo")).get("endCursor");
        assertThat(endCursor).doesNotContain("=", "+", "/");
        assertThat(decode(endCursor)).isEqualTo("[\"ACADEMY DINOSAUR\",\"1\"]");
    }

    @Test
    void decodeCursor_unescapesEveryJsonEscape() {
        String json = "[\"a\\\"b\\\\c\\u0000d\\ud83d\\ude00\\/\\n\"]";
        Field<?>[] seek = ConnectionHelper.decodeCursor(encode(json), List.of(FILM.TITLE));
        assertThat(bound(seek[0])).isEqualTo("a\"b\\c\u0000d😀/\n");
    }

    @Test
    void decodeCursor_readsANullElementAsATypedNull() {
        Field<?>[] seek = ConnectionHelper.decodeCursor(encode("[null, 1]"), List.of(FILM.TITLE, FILM.FILM_ID));
        assertThat(bound(seek[0])).isNull();
        assertThat(seek[0].getDataType().getType()).isEqualTo(String.class);
        assertThat(bound(seek[1])).isEqualTo(1);
    }

    // ===== Malformed input stays a client error =====

    @Test
    void malformedCursor_surfacesTheClientError() {
        for (String json : List.of(
                "{\"typeName\":\"Film\",\"fields\":[1]}",
                "[[1]]",
                "[1]x",
                "[\"abc",
                "[\"a\\u00",
                "[\"a\\q\"]",
                "[01]",
                "[1,]",
                "[tru]")) {
            String cursor = encode(json);
            var result = executeRaw("{ filmsConnection(first: 2, after: \"" + cursor + "\") { nodes { title } } }");
            assertThat(result.getErrors()).as(json).isNotEmpty();
            assertThat(result.getErrors().get(0).getMessage()).as(json)
                .isEqualTo("cursor is not valid (was: \"" + cursor + "\")")
                .doesNotContain("An error occurred. Reference:");
        }
    }
}
