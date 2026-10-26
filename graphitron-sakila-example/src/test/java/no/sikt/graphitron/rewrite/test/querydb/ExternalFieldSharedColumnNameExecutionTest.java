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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Execution-tier proof that an {@code @externalField} sharing its name with a column on its
 * parent table returns the method's value, and that a sibling field reading the column still
 * returns the column. The computed term is projected and read back under a reserved result-key
 * alias, never under the bare field name, so the two cannot meet.
 *
 * <p>Two shapes: {@code ComputedTitleFilm} over {@code film}, whose {@code title} returns
 * {@code lower(title)} (Sakila titles are seeded upper case), and the single-table discriminated
 * interface {@code FanLabelled} over {@code fan_base}, whose participants both declare
 * {@code label} as {@code <fan_kind>: <label>}. Fixture rows ({@code init.sql}): film 1 is
 * 'ACADEMY DINOSAUR'; fan_base rows 1-4 are ALPHA 'Alpha one', BETA 'Beta one', ALPHA 'Alpha two'
 * and BETA 'Beta two'.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ExternalFieldSharedColumnNameExecutionTest {

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

    // ===== A @table type =====

    @Test
    void tableType_computedFieldAndColumnFieldSelectedTogether_eachReturnsItsOwnValue() {
        var film = map(execute("{ computedTitleFilm(filmId: 1) { title plainTitle } }"), "computedTitleFilm");
        assertThat(film.get("title")).isEqualTo("academy dinosaur");
        assertThat(film.get("plainTitle")).isEqualTo("ACADEMY DINOSAUR");
    }

    @Test
    void tableType_computedFieldAlone_returnsTheMethodsValue() {
        var film = map(execute("{ computedTitleFilm(filmId: 1) { title } }"), "computedTitleFilm");
        assertThat(film.get("title")).isEqualTo("academy dinosaur");
    }

    @Test
    void tableType_columnFieldAlone_returnsTheColumn() {
        var film = map(execute("{ computedTitleFilm(filmId: 1) { plainTitle } }"), "computedTitleFilm");
        assertThat(film.get("plainTitle")).isEqualTo("ACADEMY DINOSAUR");
    }

    // ===== A single-table discriminated interface =====

    @Test
    void singleTableInterface_computedFieldAndColumnFieldPerParticipant() {
        var items = list(execute("""
            { allFanLabelled {
                __typename
                fanBaseId
                label
                plainLabel
            } }
            """), "allFanLabelled");
        assertThat(items)
            .extracting(i -> i.get("fanBaseId"), i -> i.get("__typename"), i -> i.get("label"), i -> i.get("plainLabel"))
            .containsExactlyInAnyOrder(
                tuple(1, "FanLabelledAlpha", "ALPHA: Alpha one", "Alpha one"),
                tuple(2, "FanLabelledBeta", "BETA: Beta one", "Beta one"),
                tuple(3, "FanLabelledAlpha", "ALPHA: Alpha two", "Alpha two"),
                tuple(4, "FanLabelledBeta", "BETA: Beta two", "Beta two"));
    }

    @Test
    void singleTableInterface_selectedThroughBothParticipantsTypeConditions() {
        var items = list(execute("""
            { allFanLabelled {
                fanBaseId
                ... on FanLabelledAlpha { label plainLabel }
                ... on FanLabelledBeta { label plainLabel }
            } }
            """), "allFanLabelled");
        assertThat(items)
            .extracting(i -> i.get("fanBaseId"), i -> i.get("label"), i -> i.get("plainLabel"))
            .containsExactlyInAnyOrder(
                tuple(1, "ALPHA: Alpha one", "Alpha one"),
                tuple(2, "BETA: Beta one", "Beta one"),
                tuple(3, "ALPHA: Alpha two", "Alpha two"),
                tuple(4, "BETA: Beta two", "Beta two"));
    }

    private static Map<String, Object> map(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    private static List<Map<String, Object>> list(Map<String, Object> parent, String field) {
        return (List<Map<String, Object>>) parent.get(field);
    }

    private Map<String, Object> execute(String query) {
        var result = graphql.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build());
        assertThat(result.getErrors()).as(query).isEmpty();
        return result.getData();
    }
}
