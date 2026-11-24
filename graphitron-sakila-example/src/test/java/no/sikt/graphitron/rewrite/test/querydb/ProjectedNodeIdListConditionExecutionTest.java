package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A list of {@code @nodeId}s handed to a {@code @condition} method through {@code argMapping} as the
 * key column of each decoded id, a {@code List<Integer>}, in each written form: the column named past
 * the list ({@code filmsOfLanguageIds}), beside a single id named the same way
 * ({@code filmsOfLanguageIdsWithOriginal}), the list reached by descending onto it through an input
 * object ({@code filmsOfPickedLanguageIds}), and at an input-field {@code @condition} applied to the
 * language table ({@code filmsOfLanguageIdsFilter}).
 *
 * <p>Each form is asserted three ways: the method filters on the decoded values, a malformed id
 * inside the list fails the request before the method runs, and an omitted list reaches a field-level
 * method as {@code null}, which the fixture reads as unconstrained. Every seeded film is in language 1
 * and none in language 2, so a list naming language 2 alone selects nothing.
 *
 * <p>Every root field here reads the whole {@code film} table, which other classes write while this
 * one runs, so each case asserts over the seeded films only.
 */
@ExecutionTier
class ProjectedNodeIdListConditionExecutionTest {

    /** The films {@code init.sql} seeds, in {@code film_id} order. */
    private static final List<Integer> SEEDED_FILM_IDS = List.of(1, 2, 3, 4, 5);

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();
        graphql = Graphitron.newGraphQL().build();
    }

    @Test
    void aKeyColumnNamedPastAListReachesTheMethodAsTheColumnOfEachId() {
        assertThat(filmIds(execute("""
            { filmsOfLanguageIds(languageIds: ["%s", "%s"]) { filmId } }
            """.formatted(languageNodeId(1), languageNodeId(2))), "filmsOfLanguageIds"))
            .isEqualTo(SEEDED_FILM_IDS);
        assertThat(filmIds(execute("""
            { filmsOfLanguageIds(languageIds: ["%s"]) { filmId } }
            """.formatted(languageNodeId(2))), "filmsOfLanguageIds"))
            .as("the method narrows on the decoded key, so the case above is not vacuously everything")
            .isEmpty();
        assertThat(filmIds(execute("""
            { filmsOfLanguageIds { filmId } }
            """), "filmsOfLanguageIds"))
            .as("an omitted list reaches the method as null, which it reads as unconstrained")
            .isEqualTo(SEEDED_FILM_IDS);
        assertThat(errorsOf("""
            { filmsOfLanguageIds(languageIds: ["%s", "not-a-node-id"]) { filmId } }
            """.formatted(languageNodeId(1))))
            .as("a malformed id inside the list is the client's error")
            .isNotEmpty();
    }

    @Test
    void aListAndASingleIdReadByOneMethodAreEachDecoded() {
        var films = dsl.select(FILM.FILM_ID)
            .from(FILM)
            .where(FILM.LANGUAGE_ID.in(1)
                .and(FILM.ORIGINAL_LANGUAGE_ID.eq(1)))
            .fetch(FILM.FILM_ID).stream()
            .filter(SEEDED_FILM_IDS::contains).toList();
        assertThat(filmIds(execute("""
            { filmsOfLanguageIdsWithOriginal(languageIds: ["%s"], originalId: "%s") { filmId } }
            """.formatted(languageNodeId(1), languageNodeId(1))), "filmsOfLanguageIdsWithOriginal"))
            .isEqualTo(films);
        assertThat(errorsOf("""
            { filmsOfLanguageIdsWithOriginal(languageIds: ["%s"], originalId: "%s") { filmId } }
            """.formatted(NodeIdEncoder.encode("ActorNode", 1), languageNodeId(1))))
            .as("an id of another node type inside the list is the client's error")
            .isNotEmpty();
    }

    @Test
    void aListReachedByDescentReachesTheMethodDecoded() {
        assertThat(filmIds(execute("""
            { filmsOfPickedLanguageIds(pick: {languageIds: ["%s"]}) { filmId } }
            """.formatted(languageNodeId(1))), "filmsOfPickedLanguageIds"))
            .isEqualTo(SEEDED_FILM_IDS);
        assertThat(filmIds(execute("""
            { filmsOfPickedLanguageIds(pick: {languageIds: ["%s"]}) { filmId } }
            """.formatted(languageNodeId(2))), "filmsOfPickedLanguageIds"))
            .isEmpty();
        assertThat(filmIds(execute("""
            { filmsOfPickedLanguageIds(pick: {}) { filmId } }
            """), "filmsOfPickedLanguageIds"))
            .isEqualTo(SEEDED_FILM_IDS);
        assertThat(errorsOf("""
            { filmsOfPickedLanguageIds(pick: {languageIds: ["not-a-node-id"]}) { filmId } }
            """))
            .as("the ids are decoded, not handed to the method as the strings the client sent")
            .isNotEmpty();
    }

    @Test
    void aListAtAnInputFieldConditionReachesTheMethodDecoded() {
        assertThat(filmIds(execute("""
            { filmsOfLanguageIdsFilter(filter: {languageIds: ["%s"]}) { filmId } }
            """.formatted(languageNodeId(1))), "filmsOfLanguageIdsFilter"))
            .isEqualTo(SEEDED_FILM_IDS);
        assertThat(filmIds(execute("""
            { filmsOfLanguageIdsFilter(filter: {languageIds: ["%s"]}) { filmId } }
            """.formatted(languageNodeId(2))), "filmsOfLanguageIdsFilter"))
            .isEmpty();
        assertThat(errorsOf("""
            { filmsOfLanguageIdsFilter(filter: {languageIds: ["not-a-node-id"]}) { filmId } }
            """))
            .isNotEmpty();
    }

    private static String languageNodeId(int languageId) {
        return NodeIdEncoder.encode("LanguageNode", languageId);
    }

    /** The seeded film ids the root field returned, in the order it returned them. */
    @SuppressWarnings("unchecked")
    private static List<Integer> filmIds(Map<String, Object> data, String field) {
        return ((List<Map<String, Object>>) data.get(field)).stream()
            .map(row -> (Integer) row.get("filmId"))
            .filter(SEEDED_FILM_IDS::contains)
            .toList();
    }

    private Map<String, Object> execute(String query) {
        ExecutionResult result = run(query);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private List<graphql.GraphQLError> errorsOf(String query) {
        return run(query).getErrors();
    }

    private ExecutionResult run(String query) {
        ExecutionInput input =
            Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }
}
