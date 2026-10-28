package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orders by a value the film's own table does not hold, paged end to end. A {@code path:} key is
 * the name of the film's original language (a nullable FK to {@code language}): English for film 5,
 * Italian for films 2 and 3, Japanese for film 1, none for film 4, which sorts on {@code NULL} and so
 * last in both directions. A {@code method:} key is the store of the film's latest stocked copy,
 * derived from the to-many {@code inventory}: store 1 for films 1 to 3, none for films 4 and 5. In
 * both, ties are broken by the {@code film_id} a connection appends, and every walk below, forward
 * and backward over two-row pages, returns each seeded film exactly once, at every site the order
 * renders at: a root connection, an {@code @orderBy} helper in either composition, a
 * {@code @splitQuery} child connection, an inline child list and a plain list root.
 *
 * <p>A third key sorts squad members by their squad's {@code member_id}, a related column named
 * like {@code squad_member}'s own key, through a list-valued {@code @orderBy}: members 1 and 2 tie
 * on it, as do 3 to 5, so the walk returns every member only if the helper appends the member's
 * own key rather than taking the same-named sort key for it.
 *
 * <p>Other classes insert films into the database this one shares, so a walk is judged on the
 * seeded rows, as {@code ConnectionTiebreakerExecutionTest} judges its own.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SortKeyOrderExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    /** By original language name, then film_id: English, the Italian tie, Japanese, then no language. */
    private static final List<Integer> ORIGINAL_ASC = List.of(5, 2, 3, 1, 4);

    /** The same key descending with the appended film_id flipped; the film with no language stays last. */
    private static final List<Integer> ORIGINAL_DESC = List.of(1, 3, 2, 5, 4);

    /** By latest stocked store, then film_id, both descending: the store-1 tie, then the unstocked films. */
    private static final List<Integer> STOCK_DESC = List.of(3, 2, 1, 5, 4);

    /** The same key ascending: the store-1 tie, then the unstocked films, each by film_id. */
    private static final List<Integer> STOCK_ASC = List.of(1, 2, 3, 4, 5);

    private static final List<Integer> SEEDED_FILM_IDS = List.of(1, 2, 3, 4, 5);

    private static final List<Integer> SEEDED_MEMBER_IDS = List.of(1, 2, 3, 4, 5);

    private static final String SELECTION =
        "{ nodes { filmId } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } }";

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    // ===== path: =====

    @Test
    void pathKey_rootConnection_forwardAndBackward() {
        Function<String, String> query = page -> "{ filmsByOriginalLanguage(" + page + ") " + SELECTION + " }";
        assertWalks(query, data -> (Map<String, Object>) data.get("filmsByOriginalLanguage"), ORIGINAL_ASC);
    }

    @Test
    void pathKey_plainListRoot_ordersByTheRelatedColumn() {
        var films = (List<Map<String, Object>>) execute("{ filmsByOriginalLanguageList { filmId } }")
            .get("filmsByOriginalLanguageList");
        assertThat(seeded(filmIdsOf(films))).containsExactlyElementsOf(ORIGINAL_ASC);
    }

    @Test
    void pathKey_namedLikeASelectedOwnColumn_keepsBothValues() {
        Function<String, String> query = page -> "{ filmsByOriginalLanguageId(" + page + ") "
            + "{ nodes { filmId languageId } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } } }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsByOriginalLanguageId");
        assertWalks(query, connection, ORIGINAL_ASC);
        var nodes = (List<Map<String, Object>>) connection.apply(execute(query.apply("first: 5"))).get("nodes");
        assertThat(nodes).as("the selected own-table language_id is not replaced by the sort key")
            .allSatisfy(node -> assertThat(node.get("languageId")).isEqualTo(1));
    }

    @Test
    void pathKey_orderByArgument_inBothRuntimeDirections() {
        Function<String, Function<String, String>> query = direction -> page ->
            "{ filmsByLanguages(order: {field: ORIGINAL_LANGUAGE, direction: " + direction + "}, " + page + ") "
                + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsByLanguages");
        assertWalks(query.apply("ASC"), connection, ORIGINAL_ASC);
        assertWalks(query.apply("DESC"), connection, ORIGINAL_DESC);
    }

    @Test
    void pathKeys_defaultOrderBase_twoKeysOverOneTable() {
        // language name (every film is English) ASC, then original language name DESC: the
        // appended film_id takes the last entry's direction.
        Function<String, String> query = page -> "{ filmsByLanguages(" + page + ") " + SELECTION + " }";
        assertWalks(query, data -> (Map<String, Object>) data.get("filmsByLanguages"), ORIGINAL_DESC);
    }

    @Test
    void pathKey_composedOrderByArgument_completedInTheHelper() {
        Function<String, String> query = page -> "{ filmsByLanguagesComposed(order: [{field: ORIGINAL_LANGUAGE,"
            + " direction: ASC}], " + page + ") " + SELECTION + " }";
        assertWalks(query, data -> (Map<String, Object>) data.get("filmsByLanguagesComposed"), ORIGINAL_ASC);
    }

    @Test
    void pathKey_splitQueryChildConnection_forwardAndBackward() {
        // Language 1 holds every seeded film.
        Function<String, String> query = page -> "{ languageBySingleKey(language_id: 1) { filmsByOriginalLanguage("
            + page + ") " + SELECTION + " } }";
        assertWalks(query, data -> (Map<String, Object>)
            ((Map<String, Object>) data.get("languageBySingleKey")).get("filmsByOriginalLanguage"), ORIGINAL_ASC);
    }

    @Test
    void pathKey_inlineChildList_ordersInTheSubselect() {
        var language = (Map<String, Object>) execute(
            "{ languageBySingleKey(language_id: 1) { filmsInlineByOriginalLanguage { filmId } } }")
            .get("languageBySingleKey");
        var films = (List<Map<String, Object>>) language.get("filmsInlineByOriginalLanguage");
        assertThat(seeded(filmIdsOf(films))).containsExactlyElementsOf(ORIGINAL_ASC);
    }

    // ===== method: =====

    @Test
    void methodKey_rootConnection_forwardAndBackward() {
        Function<String, String> query = page -> "{ filmsByLatestStock(" + page + ") " + SELECTION + " }";
        assertWalks(query, data -> (Map<String, Object>) data.get("filmsByLatestStock"), STOCK_DESC);
    }

    @Test
    void methodKey_orderByArgument_inBothRuntimeDirections() {
        Function<String, Function<String, String>> query = direction -> page ->
            "{ filmsByLanguages(order: {field: LATEST_STOCK, direction: " + direction + "}, " + page + ") "
                + SELECTION + " }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("filmsByLanguages");
        assertWalks(query.apply("ASC"), connection, STOCK_ASC);
        assertWalks(query.apply("DESC"), connection, STOCK_DESC);
    }

    @Test
    void methodKey_splitQueryChildConnection_forwardAndBackward() {
        Function<String, String> query = page -> "{ languageBySingleKey(language_id: 1) { filmsByLatestStock("
            + page + ") " + SELECTION + " } }";
        assertWalks(query, data -> (Map<String, Object>)
            ((Map<String, Object>) data.get("languageBySingleKey")).get("filmsByLatestStock"), STOCK_DESC);
    }

    @Test
    void methodKey_inlineChildList_ordersInTheSubselect() {
        var language = (Map<String, Object>) execute(
            "{ languageBySingleKey(language_id: 1) { filmsInlineByLatestStock { filmId } } }")
            .get("languageBySingleKey");
        var films = (List<Map<String, Object>>) language.get("filmsInlineByLatestStock");
        assertThat(seeded(filmIdsOf(films))).containsExactlyElementsOf(STOCK_DESC);
    }

    // ===== a sort key named like the row key =====

    @Test
    void pathKeyNamedLikeTheRowKey_composedOrderByArgument_stillAppendsTheRowKey() {
        Function<String, Function<String, String>> query = direction -> page ->
            "{ squadMembers(order: [{field: SQUAD_FOUNDER, direction: " + direction + "}], " + page + ") "
                + "{ nodes { memberId } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } } }";
        Function<Map<String, Object>, Map<String, Object>> connection =
            data -> (Map<String, Object>) data.get("squadMembers");
        assertWalks(query.apply("ASC"), connection, "memberId", SEEDED_MEMBER_IDS, List.of(1, 2, 3, 4, 5));
        assertWalks(query.apply("DESC"), connection, "memberId", SEEDED_MEMBER_IDS, List.of(5, 4, 3, 2, 1));
    }

    // ===== walks =====

    private static void assertWalks(Function<String, String> query,
                                    Function<Map<String, Object>, Map<String, Object>> connection,
                                    List<Integer> expected) {
        assertWalks(query, connection, "filmId", SEEDED_FILM_IDS, expected);
    }

    private static void assertWalks(Function<String, String> query,
                                    Function<Map<String, Object>, Map<String, Object>> connection,
                                    String idKey, List<Integer> seededIds, List<Integer> expected) {
        assertWalk(forward(query, connection, idKey), seededIds, expected, "forward");
        assertWalk(backward(query, connection, idKey), seededIds, expected, "backward");
    }

    /** Every row of a {@code first: 2} walk from the start, in the order the pages returned them. */
    private static List<Integer> forward(Function<String, String> query,
                                         Function<Map<String, Object>, Map<String, Object>> connection,
                                         String idKey) {
        var ids = new ArrayList<Integer>();
        String after = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = connection.apply(execute(query.apply(
                "first: 2" + (after == null ? "" : ", after: \"" + after + "\""))));
            ids.addAll(idsOf((List<Map<String, Object>>) conn.get("nodes"), idKey));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            after = (String) pageInfo.get("endCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasNextPage")) && ++pages < 100);
        return ids;
    }

    /** Every row of a {@code last: 2} walk from the end, put back in forward order. */
    private static List<Integer> backward(Function<String, String> query,
                                          Function<Map<String, Object>, Map<String, Object>> connection,
                                          String idKey) {
        var ids = new ArrayList<Integer>();
        String before = null;
        Map<String, Object> pageInfo;
        int pages = 0;
        do {
            var conn = connection.apply(execute(query.apply(
                "last: 2" + (before == null ? "" : ", before: \"" + before + "\""))));
            ids.addAll(0, idsOf((List<Map<String, Object>>) conn.get("nodes"), idKey));
            pageInfo = (Map<String, Object>) conn.get("pageInfo");
            before = (String) pageInfo.get("startCursor");
        } while (Boolean.TRUE.equals(pageInfo.get("hasPreviousPage")) && ++pages < 100);
        return ids;
    }

    private static void assertWalk(List<Integer> walked, List<Integer> seededIds, List<Integer> expected,
                                   String direction) {
        assertThat(walked)
            .as(direction + " walk: no row arrives twice across the page boundaries")
            .doesNotHaveDuplicates();
        assertThat(walked.stream().filter(seededIds::contains).toList())
            .as(direction + " walk: every seeded row arrives exactly once, in sort-key-then-id order")
            .containsExactlyElementsOf(expected);
    }

    private static List<Integer> seeded(List<Integer> ids) {
        return ids.stream().filter(SEEDED_FILM_IDS::contains).toList();
    }

    private static List<Integer> filmIdsOf(List<Map<String, Object>> films) {
        return idsOf(films, "filmId");
    }

    private static List<Integer> idsOf(List<Map<String, Object>> nodes, String idKey) {
        return nodes.stream().map(node -> (Integer) node.get(idKey)).toList();
    }

    private static Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }
}
