package no.sikt.graphitron.sakila.example.app;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Drift protection for the user-manual tutorial under {@code docs/manual/tutorial/}: replays
 * each page's HTTP query against the live Quarkus app and asserts on the response shape the
 * prose promises. If a directive disappears, an endpoint moves, or a generated resolver
 * narrows differently, the corresponding tutorial page fails before the docs ship.
 *
 * <p>One method per page (or per query within a page). New tutorial queries land here in
 * the same commit as the prose.
 */
@QuarkusTest
@QuarkusTestResource(SmokeTestPostgresResource.class)
@ExecutionTier
class TutorialSmokeTest {

    private static final Pattern FILM_ID = Pattern.compile("\"filmId\":(\\d+)");

    @Inject
    AgroalDataSource dataSource;

    @AfterEach
    void resetFilmTable() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM film WHERE film_id > 5");
        }
    }

    @Test
    void page1_introspectionVerification() {
        var body = post("{ __typename }");
        assertThat(body).contains("\"__typename\":\"Query\"");
    }

    @Test
    void page3_customersBasicSelection() {
        var body = post("{ customers { firstName lastName email } }");
        assertThat(body)
            .contains("\"firstName\":\"Mary\"")
            .contains("\"firstName\":\"Patricia\"")
            .contains("\"firstName\":\"Linda\"")
            .contains("\"firstName\":\"Barbara\"")
            .contains("\"firstName\":\"Elizabeth\"")
            .contains("\"email\":\"mary.smith@example.com\"");
    }

    @Test
    void page3_activeFilter() {
        var body = post("{ customers(active: true) { firstName } }");
        assertThat(body)
            .contains("\"firstName\":\"Mary\"")
            .contains("\"firstName\":\"Patricia\"")
            .contains("\"firstName\":\"Linda\"")
            .doesNotContain("\"firstName\":\"Barbara\"")
            .doesNotContain("\"firstName\":\"Elizabeth\"");
    }

    @Test
    void page4_singleHopReference() {
        var body = post("{ customers { firstName address { address district } } }");
        assertThat(body)
            .contains("\"address\":\"47 MySakila Drive\"")
            .contains("\"district\":\"Alberta\"");
    }

    @Test
    void page4_multiHopReference() {
        var body = post("{ customers { firstName storeAddress { address district } } }");
        assertThat(body)
            .contains("\"address\":\"47 MySakila Drive\"")
            .contains("\"address\":\"28 MySQL Boulevard\"");
    }

    @Test
    void page5_createAndUpdateFilm() {
        var insert = post(
            "mutation { createFilm(in: { title: \"MY FIRST FILM\", languageId: 1 }) { filmId title } }"
        );
        assertThat(insert).contains("\"title\":\"MY FIRST FILM\"");

        Matcher m = FILM_ID.matcher(insert);
        assertThat(m.find()).as("filmId in createFilm response: %s", insert).isTrue();
        int filmId = Integer.parseInt(m.group(1));
        assertThat(filmId).isGreaterThan(5);

        var update = post(
            "mutation { updateFilm(in: { filmId: " + filmId + ", title: \"RENAMED FILM\" }) "
                + "{ filmId title } }"
        );
        assertThat(update)
            .contains("\"filmId\":" + filmId)
            .contains("\"title\":\"RENAMED FILM\"");
    }

    private static String post(String query) {
        return given()
            .contentType("application/json")
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("errors", equalTo(null))
        .extract().asString();
    }
}
