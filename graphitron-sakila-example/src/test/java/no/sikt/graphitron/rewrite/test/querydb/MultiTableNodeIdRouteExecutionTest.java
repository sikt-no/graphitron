package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof for per-participant {@code @nodeId} decode routes: one filter leaf, two
 * participants, two differently-shaped paths to the same decoded target, and the right rows out of
 * each branch.
 *
 * <p>{@code LanguageStock = StockFilm | StockInventory} over {@code film} and {@code inventory}.
 * {@code film} has two foreign keys to {@code language}, so auto-discovery cannot pick one;
 * {@code inventory} has none and reaches {@code language} only through {@code film}. Neither
 * participant resolves without a stated route and no single {@code @reference} could describe both,
 * so this schema is authorable only through per-participant {@code @referenceFor}. The two routes
 * also land on opposite sides of the binding fork: the film branch binds a local
 * {@code film.language_id} tuple, the inventory branch a correlated {@code EXISTS} reaching
 * {@code language} through {@code film}.
 *
 * <p>The last two tests are the other two rungs of the ladder: an id encoded for a different node
 * type is a client error rather than an empty page, and the {@code @condition(override: true)}
 * escape hands the whole predicate to the author's method, which runs once per branch against that
 * branch's own table.
 */
@ExecutionTier
class MultiTableNodeIdRouteExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private graphql.ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    /** The films of one language, read straight from the database. */
    private List<Integer> filmIdsOfLanguage(int languageId) {
        return dsl.select(DSL.field(DSL.name("film_id"), Integer.class))
            .from(DSL.table(DSL.name("film")))
            .where(DSL.field(DSL.name("language_id"), Integer.class).eq(languageId))
            .fetch(r -> r.value1());
    }

    /** The language a film holds now, or null if the film is no longer there. */
    private Integer languageOfFilm(int filmId) {
        return dsl.select(DSL.field(DSL.name("language_id"), Integer.class))
            .from(DSL.table(DSL.name("film")))
            .where(DSL.field(DSL.name("film_id"), Integer.class).eq(filmId))
            .fetchOne(r -> r.value1());
    }

    /**
     * What the database said a language held on either side of one query.
     *
     * <p>Why a query needs a window rather than a single read. Test classes in this module run
     * concurrently against one database, and several of them insert films into language 1 and
     * remove them again; {@code junit-platform.properties} spells the hazard out. So a read taken
     * before the query is a claim about a moment, and comparing an exact set against it fails
     * whenever a sibling's film arrives or leaves in between, on a query that answered correctly.
     * Two reads bracket the query instead, which splits the answer into a part both agree on and a
     * remainder that has to be judged row by row.
     */
    private record LanguageWindow(int languageId, List<Integer> before, List<Integer> after) {
        /** Present on both sides, so present throughout: the query cannot have missed these. */
        Set<Integer> heldThroughout() {
            var held = new java.util.HashSet<>(before);
            held.retainAll(after);
            return held;
        }

        /** Present on either side, so accounted for without asking the database again. */
        Set<Integer> seenEitherSide() {
            var seen = new java.util.HashSet<>(before);
            seen.addAll(after);
            return seen;
        }
    }

    /** Brackets one query with a read of the language's films on each side. */
    private <T> T overWindow(int languageId, java.util.function.Supplier<T> query,
                             java.util.function.BiConsumer<T, LanguageWindow> assertions) {
        var before = filmIdsOfLanguage(languageId);
        var answer = query.get();
        var window = new LanguageWindow(languageId, before, filmIdsOfLanguage(languageId));
        assertions.accept(answer, window);
        return answer;
    }

    /**
     * Holds a branch's film ids to the window: every film the language kept throughout must come
     * back, and anything else that comes back must be a film of that language rather than another's.
     * Together those are what "returns exactly the films of that language" means when the
     * population can move under the query.
     */
    private void assertFilmsOfLanguage(List<Integer> answer, LanguageWindow window) {
        assertThat(answer)
            .as("every film holding language %s throughout the query", window.languageId())
            .containsAll(window.heldThroughout());
        assertEveryFilmBelongsToLanguage(answer, window);
    }

    /** The half of the rule above that a branch returning a subset of the language's films owes. */
    private void assertEveryFilmBelongsToLanguage(List<Integer> answer, LanguageWindow window) {
        var seenEitherSide = window.seenEitherSide();
        for (var filmId : answer) {
            if (seenEitherSide.contains(filmId)) continue;
            // Neither read saw it, so a sibling class both added and removed it while the query ran.
            // That film was a correct answer at the time; a film of another language never is.
            var language = languageOfFilm(filmId);
            if (language == null) continue;
            assertThat(language)
                .as("film %s came back for language %s", filmId, window.languageId())
                .isEqualTo(window.languageId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothBranchesFilterByTheirOwnRouteToTheDecodedLanguage() {
        int languageId = dsl.select(DSL.field(DSL.name("language_id"), Integer.class))
            .from(DSL.table(DSL.name("language")))
            .orderBy(DSL.field(DSL.name("language_id")))
            .limit(1)
            .fetchOne(r -> r.value1());
        assertThat(filmIdsOfLanguage(languageId))
            .as("the fixture needs at least one film in the language for either branch to say anything")
            .isNotEmpty();

        overWindow(languageId, () -> {
            Map<String, Object> data = execute("""
                { stockByLanguage(filter: { languageId: "%s" }) {
                    __typename
                    ... on StockFilm { filmId }
                    ... on StockInventory { stockFilmId }
                } }
                """.formatted(NodeIdEncoder.encode("LanguageNode", languageId)));
            return (List<Map<String, Object>>) data.get("stockByLanguage");
        }, (rows, window) -> {
            // The film branch's stated route lands the decoded key on film.language_id, so it
            // returns exactly the films of that language.
            assertFilmsOfLanguage(
                rows.stream()
                    .filter(r -> "StockFilm".equals(r.get("__typename")))
                    .map(r -> (Integer) r.get("filmId"))
                    .toList(),
                window);
            // The inventory branch's route leaves its own table, so its predicate reaches language
            // through film: every inventory row it returns is stocked with a film of that language,
            // and none of another's. Its films are a subset of the branch above's, so the same rule
            // holds them, minus the requirement to return all of them.
            var stockedFilmIds = rows.stream()
                .filter(r -> "StockInventory".equals(r.get("__typename")))
                .map(r -> (Integer) r.get("stockFilmId"))
                .toList();
            assertEveryFilmBelongsToLanguage(stockedFilmIds, window);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void adifferentLanguageSelectsADifferentSetOnBothBranches() {
        // The same leaf with a different decoded key must move both branches together; a route that
        // silently ignored its key would return the same rows here.
        var byLanguage = dsl.select(DSL.field(DSL.name("language_id"), Integer.class))
            .from(DSL.table(DSL.name("language")))
            .orderBy(DSL.field(DSL.name("language_id")))
            .limit(2)
            .fetch(r -> r.value1());
        assertThat(byLanguage).hasSize(2);

        var first = overWindow(byLanguage.get(0),
            () -> filmIdsOf(byLanguage.get(0)), this::assertFilmsOfLanguage);
        var second = overWindow(byLanguage.get(1),
            () -> filmIdsOf(byLanguage.get(1)), this::assertFilmsOfLanguage);
        assertThat(first)
            .as("two languages with the same film set would make this test vacuous")
            .isNotEqualTo(second);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> filmIdsOf(int languageId) {
        Map<String, Object> data = execute("""
            { stockByLanguage(filter: { languageId: "%s" }) {
                ... on StockFilm { filmId }
            } }
            """.formatted(NodeIdEncoder.encode("LanguageNode", languageId)));
        return ((List<Map<String, Object>>) data.get("stockByLanguage")).stream()
            .map(r -> (Integer) r.get("filmId"))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    @Test
    void anIdOfAnotherNodeTypeIsAClientError() {
        // The leaf names one node type, so a wrong-typeId id is a client mistake worth surfacing
        // rather than a filter that silently matches nothing.
        var result = executeRaw("""
            { stockByLanguage(filter: { languageId: "%s" }) { __typename } }
            """.formatted(NodeIdEncoder.encode("Film", 1)));
        assertThat(result.getErrors())
            .as("a Film id supplied where a LanguageNode id is declared")
            .isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOverrideEscapeRunsTheAuthorsMethodAgainstEachBranchesOwnTable() {
        // No route is stated and neither participant can discover one, so the whole WHERE is the
        // condition method's. It filters on the film_id column both participant tables carry, which
        // is a different column on each branch (film's own key, inventory's foreign key), so a
        // method handed the wrong table would return the wrong rows rather than none.
        Map<String, Object> data = execute("""
            { stockByLanguageOverride(filter: { languageId: "1" }) {
                __typename
                ... on StockFilm { filmId }
                ... on StockInventory { stockFilmId }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("stockByLanguageOverride");

        assertThat(rows).filteredOn(r -> "StockFilm".equals(r.get("__typename")))
            .as("film's own key equals 1 on exactly one row")
            .singleElement()
            .satisfies(r -> assertThat(r.get("filmId")).isEqualTo(1));
        assertThat(rows).filteredOn(r -> "StockInventory".equals(r.get("__typename")))
            .as("inventory's film_id is a foreign key, so every row it returns stocks film 1")
            .isNotEmpty()
            .allSatisfy(r -> assertThat(r.get("stockFilmId")).isEqualTo(1));
    }
}
