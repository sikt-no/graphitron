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

/**
 * End-to-end {@code @pivot}: discriminator-keyed aggregate projections against a real PostgreSQL
 * database. Pins the pivot's behavioural invariants: values land on the right slots; an
 * unpopulated discriminator value returns a null slot; a row-less parent yields a projection
 * record with every slot null, never a null record, on both deliveries (the split path's
 * key-preserving left join); the composite-key pivot keys correctly; inline and split return
 * identical results; the selection set gates the projected slots; and one projection type reached
 * three ways in one schema (a {@code @pivot} field, an ordinary nested object on a compatible
 * {@code @table} parent, a field of a class-backed {@code @service} result) reads correctly on
 * every source shape through the one registered fetcher per slot coordinate.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class PivotExecutionTest {

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
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private Map<String, Object> filmById(int filmId, String selection) {
        var data = execute("{ filmById(film_id: [" + filmId + "]) { " + selection + " } }");
        var films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        return films.get(0);
    }

    @Test
    void pivotedValues_landOnTheRightSlots_andUnpopulatedSlotIsNull() {
        var film = filmById(1, "titleTranslations { nn nb se en }");
        var texts = (Map<String, Object>) film.get("titleTranslations");
        assertThat(texts).containsEntry("nb", "AKADEMIET DINOSAUR")
            .containsEntry("nn", "AKADEMIET DINOSAUR (nynorsk)")
            .containsEntry("se", null)
            .containsEntry("en", null);
    }

    @Test
    void valueColumnIsPerUsage_sameProjectionTypeReadsTaglineColumn() {
        var film = filmById(3, "titleTranslations { nn nb } taglineTranslations { nn nb }");
        var titles = (Map<String, Object>) film.get("titleTranslations");
        var taglines = (Map<String, Object>) film.get("taglineTranslations");
        assertThat(titles).containsEntry("nb", "TILPASNINGSHOL").containsEntry("nn", null);
        assertThat(taglines).containsEntry("nb", null).containsEntry("nn", "Ein sær komedie");
    }

    @Test
    void rowLessParent_yieldsRecordOfNullSlots_notNullRecord_onBothDeliveries() {
        // Film 2 has no film_translation rows at all. One projection record exists per parent,
        // always: absence surfaces as null slots, never as a null record — the split delivery's
        // key-preserving left join is what keeps this identical to inline.
        var film = filmById(2,
            "titleTranslations { nn nb } titleTranslationsSplit { nn nb }");
        var inline = (Map<String, Object>) film.get("titleTranslations");
        var split = (Map<String, Object>) film.get("titleTranslationsSplit");
        assertThat(inline).isNotNull().containsEntry("nn", null).containsEntry("nb", null);
        assertThat(split).isNotNull().containsEntry("nn", null).containsEntry("nb", null);
    }

    @Test
    void inlineAndSplit_returnIdenticalResults() {
        var film = filmById(1,
            "titleTranslations { nn nb se en } titleTranslationsSplit { nn nb se en }"
            + " prices { NOK USD EUR } pricesSplit { NOK USD EUR }");
        assertThat(film.get("titleTranslationsSplit")).isEqualTo(film.get("titleTranslations"));
        assertThat(film.get("pricesSplit")).isEqualTo(film.get("prices"));
    }

    @Test
    void numericValueColumn_identityTokenMapping() {
        var film = filmById(1, "prices { NOK USD EUR }");
        var prices = (Map<String, Object>) film.get("prices");
        assertThat(((Number) prices.get("NOK")).doubleValue()).isEqualTo(49.90);
        assertThat(((Number) prices.get("EUR")).doubleValue()).isEqualTo(4.20);
        assertThat(prices.get("USD")).isNull();
    }

    @Test
    void compositeKeyPivot_keysOnBothColumns() {
        // (actor 1, film 1) carries nob+eng notes; (actor 2, film 1) has none — the composite
        // correlation must not leak actor 1's notes onto actor 2's coordinate, and the row-less
        // coordinate still gets a record of null slots.
        var data = execute("""
            { filmActorsByKey(key: [{filmId: 1, actorId: 1}, {filmId: 1, actorId: 2}]) {
                actorId
                noteTranslations { nb en }
            } }
            """);
        var actors = (List<Map<String, Object>>) data.get("filmActorsByKey");
        var byActor = new java.util.HashMap<Integer, Map<String, Object>>();
        for (var fa : actors) {
            byActor.put((Integer) fa.get("actorId"), (Map<String, Object>) fa.get("noteTranslations"));
        }
        assertThat(byActor.get(1)).containsEntry("nb", "hovedrolle").containsEntry("en", "lead role");
        assertThat(byActor.get(2)).isNotNull()
            .containsEntry("nb", null).containsEntry("en", null);
    }

    @Test
    void selectionSubset_projectsOnlySelectedSlots() {
        // A two-slot selection must not fail on (or leak) the unselected slots; behaviourally the
        // narrowed projection is observable as a plain successful read of the subset.
        var film = filmById(1, "titleTranslations { nn nb }");
        var texts = (Map<String, Object>) film.get("titleTranslations");
        assertThat(texts).containsOnlyKeys("nn", "nb");
    }

    @Test
    void aliasedDuplicateSelections_resolveIndependently() {
        var film = filmById(1, "a: titleTranslations { nb } b: titleTranslations { nn }");
        assertThat((Map<String, Object>) film.get("a")).containsEntry("nb", "AKADEMIET DINOSAUR");
        assertThat((Map<String, Object>) film.get("b"))
            .containsEntry("nn", "AKADEMIET DINOSAUR (nynorsk)");
    }

    @Test
    void oneProjectionType_readsCorrectlyOnAllThreeSourceShapes() {
        // (1) pivot record, (2) nesting parent's table row, (3) class-backed @service record —
        // one registered fetcher per slot coordinate serves all three, including the run-time
        // dispatch between the by-name Record arm and the accessor arm.
        var pivotArm = filmById(1, "titleTranslations { nn nb }");
        assertThat((Map<String, Object>) pivotArm.get("titleTranslations"))
            .containsEntry("nb", "AKADEMIET DINOSAUR");

        var hosts = execute("{ pivotHosts { hostId texts { nn nb se en } } }");
        var host = ((List<Map<String, Object>>) hosts.get("pivotHosts")).get(0);
        assertThat((Map<String, Object>) host.get("texts"))
            .containsEntry("nn", "nn-vert")
            .containsEntry("nb", "nb-vert")
            .containsEntry("se", null)
            .containsEntry("en", "en-host");

        var service = execute("{ pivotServiceTexts { nn nb se en } }");
        assertThat((Map<String, Object>) service.get("pivotServiceTexts"))
            .containsEntry("nn", "service-nn")
            .containsEntry("nb", "service-nb")
            .containsEntry("se", null)
            .containsEntry("en", "service-en");
    }
}
