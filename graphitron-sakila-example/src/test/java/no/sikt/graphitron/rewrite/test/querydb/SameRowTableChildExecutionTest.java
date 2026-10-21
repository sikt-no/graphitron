package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A child field returning a {@code @table} type bound to its parent's own table, with no
 * {@code @reference}, against a real PostgreSQL database: it reads the parent's own row, so every
 * parent gets its own values. Fixtures: {@code Film.sameRow: FilmSameRow} (both on {@code film}),
 * whose type holds a foreign-key child and a cycle back to {@code Film};
 * {@code Film.sameRowNested: FilmSameRowNest}, a plain nested object holding the same child;
 * {@code PivotNestingHost.labelsRow: HostLabelsRow} (both on {@code pivot_nesting_host}) for the
 * null rule, where host 1 carries {@code nn} and {@code nb}, host 2 neither and host 3 only
 * {@code nb}; and {@code Film.sameTableById}, the narrowed form, a read of {@code film} by its own
 * argument that is the same under every film.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class SameRowTableChildExecutionTest {

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

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    /** The films keyed by {@code filmId}, each carrying {@code selection}. */
    private Map<Integer, Map<String, Object>> films(String selection) {
        var byId = new HashMap<Integer, Map<String, Object>>();
        for (var film : (List<Map<String, Object>>) execute("{ films { filmId " + selection + " } }").get("films")) {
            byId.put((Integer) film.get("filmId"), film);
        }
        return byId;
    }

    private Map<Integer, Map<String, Object>> hosts(String selection) {
        var byId = new HashMap<Integer, Map<String, Object>>();
        for (var host : (List<Map<String, Object>>) execute("{ pivotHosts { hostId " + selection + " } }").get("pivotHosts")) {
            byId.put((Integer) host.get("hostId"), host);
        }
        return byId;
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @Test
    void everyFilm_getsItsOwnRow() {
        var films = films("title sameRow { filmId title }");
        assertThat(films).hasSizeGreaterThan(1);
        films.forEach((id, film) -> {
            assertThat(child(film, "sameRow")).containsEntry("filmId", id);
            assertThat(child(film, "sameRow")).containsEntry("title", film.get("title"));
        });
        assertThat(films.values()).extracting(f -> child(f, "sameRow").get("title")).doesNotHaveDuplicates();
    }

    /**
     * {@code languageName} is selected on the host and on the child: both project it under the
     * same result-key alias, each in its own record scope, and each reads its own film's row.
     */
    @Test
    void foreignKeyChild_correlatesOnTheParentRow_atBothLevels() {
        var films = films("languageId languageName sameRow { languageName language { languageId name } }");
        films.values().forEach(film -> {
            var sameRow = child(film, "sameRow");
            assertThat(sameRow).containsEntry("languageName", film.get("languageName"));
            assertThat(child(sameRow, "language")).containsEntry("languageId", film.get("languageId"));
        });
    }

    /** Inside a plain nested object the child still correlates on the film the object nests on. */
    @Test
    void underANestedObject_everyFilm_getsItsOwnRow() {
        var films = films("title languageId sameRowNested { row { filmId title language { languageId } } }");
        assertThat(films).hasSizeGreaterThan(1);
        films.forEach((id, film) -> {
            var row = child(child(film, "sameRowNested"), "row");
            assertThat(row).containsEntry("filmId", id).containsEntry("title", film.get("title"));
            assertThat(child(row, "language")).containsEntry("languageId", film.get("languageId"));
        });
    }

    @Test
    void resultKeys_sharedBetweenHostAndChild_stayApart() {
        // The host's x is a scalar subselect and the child's x an object read; both project under
        // the result key, each in its own record scope, and each projection sees only the fields
        // selected at its own level.
        var films = films("x: languageName sameRow { x: language { name } y: title }");
        films.values().forEach(film -> {
            assertThat(film.get("x")).isInstanceOf(String.class);
            assertThat(child(film, "sameRow").get("x")).isInstanceOf(Map.class);
            assertThat(((String) child(child(film, "sameRow"), "x").get("name")).trim())
                .isEqualTo(((String) film.get("x")).trim());
        });
    }

    @Test
    void sameTableCycle_twoLevelsDeep_returnsTheRightRows() {
        var films = films("sameRow { film { filmId sameRow { filmId title } } }");
        films.forEach((id, film) -> {
            var inner = child(child(film, "sameRow"), "film");
            assertThat(inner).containsEntry("filmId", id);
            assertThat(child(inner, "sameRow")).containsEntry("filmId", id);
        });
    }

    @Test
    void nullableField_withEveryColumnNull_resolvesToNull() {
        var hosts = hosts("labelsRow { nn nb }");
        assertThat(hosts.get(2)).containsEntry("labelsRow", null);
        assertThat(child(hosts.get(1), "labelsRow")).containsEntry("nn", "nn-vert").containsEntry("nb", "nb-vert");
    }

    @Test
    void nullRule_isDecidedOverEveryField_notTheSelection() {
        var hosts = hosts("labelsRow { nn }");
        assertThat(child(hosts.get(3), "labelsRow")).isNotNull().containsEntry("nn", null);
        assertThat(hosts.get(2)).containsEntry("labelsRow", null);
    }

    /**
     * The sis {@code Karakter.navnAlleSprak} shape: a same-table child whose type holds a list
     * besides its columns. Host 2's columns are null and its language is unset, so the list is
     * empty and the child resolves to null, as Graphitron 9 resolved it; host 3 carries nb.
     */
    @Test
    void sameTableChildHoldingAList_withColumnsNullAndTheListEmpty_resolvesToNull() {
        var hosts = hosts("labelsWithFilms { nn nb languageFilms { title } }");
        assertThat(hosts.get(2)).containsEntry("labelsWithFilms", null);
        assertThat(child(hosts.get(3), "labelsWithFilms")).containsEntry("nb", "nb-only");
        assertThat(hosts("labelsWithFilms { nn }").get(2)).containsEntry("labelsWithFilms", null);
    }

    @Test
    void narrowedForm_readsTheFilteredRows_theSameUnderEveryParent() {
        var films = films("sameTableById(filmId: 2) { filmId title }");
        assertThat(films).hasSizeGreaterThan(1);
        films.values().forEach(film ->
            assertThat((List<Map<String, Object>>) film.get("sameTableById"))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("filmId", 2)));
    }
}
