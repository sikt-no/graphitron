package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.classpath.ClasspathScanner;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE_LINK_RULE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each link of a field's chain departs and arrives, toward each of the field's targets.
 *
 * <p>Captured for real rather than seeded, on {@code FieldRoutinesTest}'s terms: the claim is that
 * an authored key name met a live catalog and that the chain's own order decided which way the key
 * was traversed, so a seeded row would describe a resolution nothing performed.
 *
 * <p>The claim worth testing is the one the read-time rule could not make. A foreign key is a hop in
 * either direction, and the fixture writes the same key at two coordinates that traverse it opposite
 * ways; what tells them apart is where each chain stands when it reaches the element.
 */
@PipelineTier
class FieldTableLinksTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Film @table(name: "film") {
          title: String
          actors: [FilmActor!]! @reference(path: [{key: "film_actor_film_id_fkey"}])
          language: Language @reference(path: [{table: "language"}])
          junction: [FilmActor!]! @reference(path: [{condition: {
              className: "no.sikt.graphitron.rewrite.TestConditionRoutes",
              method: "filmToFilmActor"
          }}])
          rentals: [Rental!]! @reference(path: [{condition: {
              className: "no.sikt.graphitron.rewrite.TestConditionRoutes",
              method: "routineResultToRental"
          }}])
        }
        type Language @table(name: "language") { name: String }
        type FilmActor @table(name: "film_actor") {
          film: Film @reference(path: [{key: "film_actor_film_id_fkey"}])
          actor: Actor @reference(path: [{table: "actor"}])
        }
        type Actor @table(name: "actor") {
          films: [Film!]!
            @reference(path: [{key: "film_actor_actor_id_fkey"},
                              {key: "film_actor_film_id_fkey"}])
        }
        type Rental @table(name: "rental") { rentalId: Int @field(name: "rental_id") }
        type Row { title: String }
        type Query {
          films: [Film!]!
          actors: [Actor!]!
          rows(actorId: Int!, minLength: Int!): [Row!]!
            @routine(name: "films_for_actor",
                     argMapping: "pActorId: actorId, pMinLength: minLength")
            @defaultOrder(fields: [{name: "film_id"}])
          hopped(actorId: Int!, minLength: Int!): [Film!]!
            @routine(name: "films_for_actor",
                     argMapping: "pActorId: actorId, pMinLength: minLength")
            @reference(path: [{table: "film"}])
            @defaultOrder(primaryKey: true)
        }
        """;

    /**
     * One key, traversed against its own direction. {@code film_actor_film_id_fkey} is declared on
     * {@code film_actor}, and a chain standing on {@code film} reaches {@code film_actor} by it, so
     * the departure is the referenced side and the link runs against the key.
     */
    @Test
    @DisplayName("a key reached from the referenced side runs against it")
    void aKeyReachedFromTheParentRunsAgainstIt() {
        withCaptured(dsl -> assertThat(links(dsl, "Film", "actors"))
            .containsExactly("[film_actor] 0 KEY film -> film_actor"
                + " via film_actor.film_actor_film_id_fkey, fk_on_from=false"));
    }

    /**
     * The same key at the other coordinate. A chain standing on {@code film_actor} declares the key
     * itself, so the link runs along it and arrives at {@code film}. Read beside the case above,
     * this pair is the relation's whole point: one catalog key, two coordinates, and the orientation
     * is a consequence of where the chain stands rather than a candidacy a reader narrows.
     */
    @Test
    @DisplayName("the same key reached from the declaring side runs along it")
    void theSameKeyFromTheChildRunsAlongIt() {
        withCaptured(dsl -> assertThat(links(dsl, "FilmActor", "film"))
            .containsExactly("[film] 0 KEY film_actor -> film"
                + " via film_actor.film_actor_film_id_fkey, fk_on_from=true"));
    }

    /**
     * Two links, and the second one's departure is the first one's arrival. This is what the
     * sequence buys: the second element names a key whose two ends are {@code film_actor} and
     * {@code film}, and nothing about the element says which of them the chain is standing on.
     */
    @Test
    @DisplayName("a two-element path resolves each link from where the one before it arrived")
    void eachLinkDepartsWhereTheLastArrived() {
        withCaptured(dsl -> assertThat(links(dsl, "Actor", "films"))
            .containsExactly(
                "[film] 0 KEY actor -> film_actor"
                    + " via film_actor.film_actor_actor_id_fkey, fk_on_from=false",
                "[film] 1 KEY film_actor -> film"
                    + " via film_actor.film_actor_film_id_fkey, fk_on_from=true"));
    }

    /**
     * A routine link departs from nothing, its result being where the chain's rows begin, and the
     * three departure columns are null together because that is the fact rather than a gap. The
     * return binds no table of its own, so the result is also the chain's target and the one link
     * both begins and ends it.
     */
    @Test
    @DisplayName("a routine link arrives at its result and departs from nothing")
    void aRoutineLinkDepartsFromNothing() {
        withCaptured(dsl -> assertThat(links(dsl, "Query", "rows"))
            .containsExactly("[films_for_actor] 0 ROUTINE (none) -> films_for_actor"));
    }

    /**
     * A table element states the arrival and leaves the route to be found, which at a coordinate is
     * a lookup: the one foreign key joining the departure to it. Here the key is declared on the
     * departure, so the link runs along it, and nothing in the element said so.
     */
    @Test
    @DisplayName("a table element joins by the one foreign key between the two ends")
    void aTableElementFindsTheKeyBetweenTheEnds() {
        withCaptured(dsl -> assertThat(links(dsl, "FilmActor", "actor"))
            .containsExactly("[actor] 0 TABLE film_actor -> actor"
                + " via film_actor.film_actor_actor_id_fkey, fk_on_from=true"));
    }

    /**
     * Where two foreign keys join the same pair of tables the element has not said which, and this
     * arm cannot: a table names the arrival and a key names the route, so an author meaning one of
     * the two writes it. The link draws no row, which the chain's own length makes visible, and
     * saying which was meant is the coordinate's to answer.
     *
     * <p>The fixture is sakila's own: film references language twice, once for the language and
     * once for the original language.
     */
    @Test
    @DisplayName("a table element with two foreign keys between the ends resolves to neither")
    void twoKeysBetweenTheEndsResolveToNeither() {
        withCaptured(dsl -> {
            assertThat(links(dsl, "FilmActor", "actor"))
                .as("an empty relation would satisfy the absence below without meaning it")
                .isNotEmpty();
            assertThat(links(dsl, "Film", "language")).isEmpty();
        });
    }

    /**
     * The chain that needs both arms and the reason the sequence is what resolves it. Link 0 is the
     * routine, which departs from nothing and arrives at the function result; link 1 names a table
     * and departs from that result, which declares no foreign key, so the route is the whole
     * primary-key name match instead. Neither link could be resolved by reading its own element.
     */
    @Test
    @DisplayName("a table element leaving a function result joins by the name match")
    void aTableElementLeavingAFunctionMatchesByName() {
        withCaptured(dsl -> assertThat(links(dsl, "Query", "hopped"))
            .containsExactly(
                "[film] 0 ROUTINE (none) -> films_for_actor",
                "[film] 1 NAME_MATCH films_for_actor -> film"));
    }

    /**
     * An element carrying a condition and naming neither a key nor a table has no foreign key to
     * read, so its route is the one its method's signature declares: the parameter after the first
     * names the arrival. Nothing here resolves through a name with no coordinate attached.
     */
    @Test
    @DisplayName("a condition element routes by its method's signature")
    void aConditionElementRoutesByItsSignature() {
        withCaptured(dsl -> assertThat(links(dsl, "Film", "junction"))
            .containsExactly("[film_actor] 0 CONDITION film -> film_actor"));
    }

    /**
     * The predicate that replaced two mechanisms the read-time rule needed, and the reason it could
     * drop both: it answered for a class and a method with no coordinate, so it enumerated every
     * table as a candidate departure and demanded every overload agree. Here the departure is known,
     * and a first parameter naming a different table says this link is not travelling through this
     * method. {@code routineResultToRental} departs a routine result, so at a coordinate standing on
     * {@code film} it contradicts the chain and draws no row.
     */
    @Test
    @DisplayName("a condition whose first parameter names another table routes nothing")
    void aConditionContradictingTheDepartureRoutesNothing() {
        withCaptured(dsl -> {
            assertThat(links(dsl, "Film", "junction"))
                .as("an empty relation would satisfy the absence below without meaning it")
                .isNotEmpty();
            assertThat(links(dsl, "Film", "rentals")).isEmpty();
        });
    }

    /**
     * The identity a reader can check, and the reason the target is part of the key: a chain that
     * resolved to its end arrives at the table the field claims to return from.
     */
    @Test
    @DisplayName("the last link of a resolved chain arrives at the target")
    void theLastLinkArrivesAtTheTarget() {
        withCaptured(dsl -> {
            var t = GRAPHITRON_FIELD_TABLE_LINK;
            var last = dsl.select(t.TYPE_NAME, t.FIELD_NAME, t.TARGET_TABLE, t.TO_TABLE, t.POSITION)
                .from(t)
                .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .fetch();
            assertThat(last).isNotEmpty();
            assertThat(last.stream()
                .filter(r -> !dsl.fetchExists(dsl.selectFrom(t)
                    .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
                    .and(t.TYPE_NAME.eq(r.value1())).and(t.FIELD_NAME.eq(r.value2()))
                    .and(t.TARGET_TABLE.eq(r.value3()))
                    .and(t.POSITION.gt(r.value5()))))
                .map(r -> r.value1() + "." + r.value2() + " -> " + r.value4()
                    + " (target " + r.value3() + ")")
                .toList())
                .as("every chain's last link lands on the table its own key names as the target")
                .allSatisfy(row -> assertThat(row)
                    .matches(".* -> (\\w+) \\(target \\1\\)"));
        });
    }

    @Test
    @DisplayName("a field with no chain draws no link")
    void aFieldWithoutAChainDrawsNothing() {
        withCaptured(dsl -> {
            assertThat(links(dsl, "Film", "actors"))
                .as("an empty relation would satisfy the absence below without meaning it")
                .isNotEmpty();
            assertThat(links(dsl, "Query", "films")).isEmpty();
        });
    }

    /**
     * That the stage loses nothing between the rule and the table. It is a weak claim and worth
     * saying so: the stage is an INSERT from the view, so the two agree by construction and this
     * fails only where the insert drops a row the rule stated. The conversion's own oracle is the
     * cases above, which say what each arm resolves to and did so before the rule moved into the
     * catalog.
     *
     * <p>What this adds is a place to read the disagreement from. It is stated against this
     * fixture because this is the one exercising every arm, a key traversed both ways, a table
     * element, a table element out of a function result, two condition signatures and a two-key
     * chain, and it reports the links and their readings on failure, which is where a lost
     * resolution is diagnosed.
     *
     * <p>Both directions, because they fail differently. Rows the rule does not produce are a
     * resolution the view lost; rows the table does not hold are one it invented.
     */
    @Test
    @DisplayName("the stored links are exactly what the rule states, both ways")
    void theTableAndTheRuleAgree() {
        withCaptured(dsl -> {
            assertThat(ruleRows(dsl))
                .as("the rule and the table disagree.%n  links:    %s%n  readings: %s",
                    chainLinks(dsl), readings(dsl))
                .containsExactlyInAnyOrderElementsOf(tableRows(dsl));
        });
    }

    /** Every written chain link, which is what a reading is a reading of. */
    private static List<String> chainLinks(DSLContext dsl) {
        var l = no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK;
        return dsl.select(l.TYPE_NAME, l.FIELD_NAME, l.POSITION, l.SOURCE_LINE, l.SOURCE_COLUMN)
            .from(l).where(l.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(l.TYPE_NAME, l.FIELD_NAME, l.POSITION)
            .fetch(FieldTableLinksTest::line);
    }

    /**
     * Every reading of every link, which is what a disagreement is diagnosed from: a link the rule
     * dropped has either no reading here or several, and the difference is the whole question.
     */
    private static List<String> readings(DSLContext dsl) {
        var r = no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_LINK_READING;
        return dsl.select(r.TYPE_NAME, r.FIELD_NAME, r.TARGET_TABLE, r.POSITION, r.LAST_POSITION,
                r.VIA, r.CONSTRAINT_NAME, r.FK_ON_FROM, r.FROM_TABLE, r.TO_TABLE)
            .from(r).where(r.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(r.TYPE_NAME, r.FIELD_NAME, r.POSITION)
            .fetch(FieldTableLinksTest::line);
    }

    /** Every stored link of the fixture's graph, as a comparable line. */
    private static List<String> tableRows(DSLContext dsl) {
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        return dsl.select(t.TYPE_NAME, t.FIELD_NAME, t.TARGET_TABLE, t.POSITION, t.VIA,
                t.KEY_MATCHED_BY, t.CONSTRAINT_NAME, t.FK_ON_FROM, t.FROM_TABLE, t.TO_TABLE)
            .from(t).where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .fetch(FieldTableLinksTest::line);
    }

    /** The same, as the rule states it. */
    private static List<String> ruleRows(DSLContext dsl) {
        var r = GRAPHITRON_FIELD_TABLE_LINK_RULE;
        return dsl.select(r.TYPE_NAME, r.FIELD_NAME, r.TARGET_TABLE, r.POSITION, r.VIA,
                r.KEY_MATCHED_BY, r.CONSTRAINT_NAME, r.FK_ON_FROM, r.FROM_TABLE, r.TO_TABLE)
            .from(r).where(r.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .fetch(FieldTableLinksTest::line);
    }

    private static String line(org.jooq.Record r) {
        var parts = new java.util.ArrayList<String>();
        for (int i = 0; i < r.size(); i++) {
            parts.add(String.valueOf(r.get(i)));
        }
        return String.join(" ", parts);
    }

    private static List<String> links(DSLContext dsl, String typeName, String fieldName) {
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        return dsl.select(t.TARGET_TABLE, t.POSITION, t.VIA, t.FROM_TABLE, t.TO_TABLE,
                t.CONSTRAINT_TABLE, t.CONSTRAINT_NAME, t.FK_ON_FROM)
            .from(t)
            .where(t.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(t.TYPE_NAME.eq(typeName))
            .and(t.FIELD_NAME.eq(fieldName))
            .orderBy(t.TARGET_TABLE, t.POSITION)
            .fetch(r -> "[" + r.value1() + "] " + r.value2() + " " + r.value3() + " "
                + (r.value4() == null ? "(none)" : r.value4()) + " -> " + r.value5()
                + (r.value7() == null ? ""
                   : " via " + r.value6() + "." + r.value7() + ", fk_on_from=" + r.value8()));
    }

    /**
     * The catalog and the classpath both, because the arms need both: a key or a table element
     * meets the catalog, and a condition element's route is read off the signature the
     * {@code code_} family captured. The census is the walk's transcription of a classpath and the
     * class root is that family's reading of one, and a run does both.
     */
    private void withCaptured(Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, SDL, jooq(), census(),
                testClassRoot())) {
            body.accept(store.dsl());
        }
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }

    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(FieldTableLinksTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
