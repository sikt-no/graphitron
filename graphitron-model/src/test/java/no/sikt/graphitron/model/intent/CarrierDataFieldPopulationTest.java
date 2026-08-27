package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_DATA_FIELD;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedBoundTable;
import static no.sikt.graphitron.model.test.SeededStore.seedError;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which types {@code intent_carrier_data_field} draws its population from, and how many data
 * channels each of them is told it has.
 *
 * <p>A seeded case pinning the relation's own algebra rather than a shape a build reaches: the
 * behavioural anchor over a captured store lives beside the generator, and this one exists because
 * the population and the arity are decided twice inside the view body. The rule tests, inside its
 * windowed derivation, that a candidate type is a payload some mutation-rooted producer returns,
 * and its outer query joins the producers on the same two columns and so tests it again. Two
 * statements of one condition agree until one of them changes, and the state where nothing fails
 * when they diverge is the state that precedes drift. These cases are what fails.
 *
 * <p>So the fixture holds object types no mutation-rooted producer names at all and one a
 * query-rooted producer names, both of them inside the population the derivation faces before it
 * tests anything, and it asserts the arity over every partition. What that makes checkable is
 * stated at the strength it holds. A statement of the condition that drops rows, or one that
 * multiplies its driving rows, fails here: the second is the live hazard, the producer relation
 * reporting a row per family where the count inside the derivation is over data channels, so the
 * two readings are only kept apart by projecting the population test down to the two columns it
 * tests. A statement that merely admits more than the other does not fail here, and that is the
 * redundancy itself rather than a gap in the fixture: the outer join tests the same condition, so a
 * widened derivation costs work and changes no answer. Removing the population test outright is
 * that case, and these cases pass with it removed, which is the redundancy claim confirmed by
 * execution rather than by reading.
 */
class CarrierDataFieldPopulationTest {

    // ===== The population, and where it stops =====

    /**
     * Carrier-ness comes from the producing field, so an object type shaped exactly like a payload
     * contributes nothing when nothing produces it, and neither does one a query-rooted producer
     * returns. The second is the discriminating shape: a producer row exists for it and only its
     * root operation keeps it out, which is the condition this relation states twice.
     */
    @Test
    void aTypeNoMutationRootedProducerReturnsNamesNothing() {
        withCarriers(dsl -> {
            assertThat(carriers(dsl, "FilmHolder")).as("nothing produces it").isEmpty();
            assertThat(carriers(dsl, "FilmResult")).as("a query-rooted producer does").isEmpty();
        });
    }

    /**
     * The whole graph, so a type that should contribute nothing cannot hide behind a projection and
     * a widened population fails here rather than at whichever reader met it first.
     */
    @Test
    void theGraphNamesOnlyThePayloadsAMutationRootReturns() {
        withCarriers(dsl -> assertThat(carriers(dsl)).containsExactly(
            "CreateFilmPayload.film SERVICE TABLE 1",
            "FilmPayload.film DML TABLE 1",
            "FilmPayload.film SERVICE TABLE 1",
            "MultiPayload.actor SERVICE TABLE 2",
            "MultiPayload.film SERVICE TABLE 2"));
    }

    // ===== The arity over a partition =====

    /**
     * The count is over the payload's data channels, the errors channel not being one of them. Two
     * channels are two rows counting two, which is a partition wide enough that a driving row
     * counted twice changes the number rather than staying inside a one-row partition.
     */
    @Test
    void aPartitionCountsItsOwnDataChannelsAndNotTheErrorsChannel() {
        withCarriers(dsl -> {
            assertThat(carriers(dsl, "MultiPayload")).containsExactly(
                "MultiPayload.actor SERVICE TABLE 2",
                "MultiPayload.film SERVICE TABLE 2");
            assertThat(carriers(dsl, "CreateFilmPayload"))
                .containsExactly("CreateFilmPayload.film SERVICE TABLE 1");
        });
    }

    /**
     * A payload two families return is a row per family, and the arity stays the payload's own. The
     * multiplicity is the outer join's to introduce and the count inside the derivation is blind to
     * it, so a population test carrying the family through would double this number while leaving
     * the row set looking right.
     */
    @Test
    void aPayloadTwoFamiliesReturnIsARowPerFamilyAtItsOwnArity() {
        withCarriers(dsl -> assertThat(carriers(dsl, "FilmPayload")).containsExactly(
            "FilmPayload.film DML TABLE 1",
            "FilmPayload.film SERVICE TABLE 1"));
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "com.example.jooq";
    private static final String PUBLIC = "public";
    private static final String SERVICE = "com.example.FilmService";

    /**
     * Five payload-shaped object types over two bound element types and one error channel: one
     * returned by a mutation-rooted {@code @service}, one by two producers of different families,
     * one declaring two data channels, one returned by a query-rooted {@code @service} and one no
     * producer names at all.
     *
     * <p>The last two are the fixture's negatives, and they sit in the population the windowed
     * derivation faces before it tests anything, so the boundary the cases pin is the rule's own
     * rather than whatever a fixture happened to declare. Which of them the outer join alone would
     * have excluded is the class comment's subject.
     */
    private static void withCarriers(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedBoundTable(dsl, GRAPH, "Film", "film", PKG, PUBLIC, "film");
            seedBoundTable(dsl, GRAPH, "Actor", "actor", PKG, PUBLIC, "actor");
            seedField(dsl, GRAPH, "Film", "title");
            seedField(dsl, GRAPH, "Actor", "firstName");

            seedUnionMember(dsl, GRAPH, "WriteError", "DbErr", 0);
            seedError(dsl, GRAPH, "DbErr");
            seedField(dsl, GRAPH, "DbErr", "message");

            payload(dsl, "CreateFilmPayload", "film", "Film");
            payload(dsl, "FilmPayload", "film", "Film");
            payload(dsl, "MultiPayload", "film", "Film");
            seedField(dsl, GRAPH, "MultiPayload", "actor", "Actor", false);
            payload(dsl, "FilmResult", "film", "Film");
            payload(dsl, "FilmHolder", "film", "Film");

            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "CreateFilmPayload", false);
            seedService(dsl, GRAPH, "Mutation", "createFilm", SERVICE, "create");
            seedField(dsl, GRAPH, "Mutation", "serviceFilm", "FilmPayload", false);
            seedService(dsl, GRAPH, "Mutation", "serviceFilm", SERVICE, "create");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "FilmPayload", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE", "film");
            seedField(dsl, GRAPH, "Mutation", "createBoth", "MultiPayload", false);
            seedService(dsl, GRAPH, "Mutation", "createBoth", SERVICE, "create");

            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedField(dsl, GRAPH, "Query", "findFilm", "FilmResult", false);
            seedService(dsl, GRAPH, "Query", "findFilm", SERVICE, "find");
            seedField(dsl, GRAPH, "Query", "holder", "FilmHolder", false);

            body.accept(dsl);
        });
    }

    /** One data channel of the stated element type, beside the errors channel every payload has. */
    private static void payload(DSLContext dsl, String typeName, String fieldName, String namedType) {
        seedField(dsl, GRAPH, typeName, fieldName, namedType, false);
        seedField(dsl, GRAPH, typeName, "errors", "WriteError", true);
    }

    /** Every carrier data field the graph holds, one string per row. */
    private static List<String> carriers(DSLContext dsl) {
        return carriers(dsl, null);
    }

    /**
     * The same restricted to one payload type, for a case whose subject is that partition. The
     * coordinate, the producing family, the element kind and the arity, so a case states the whole
     * of what a row says rather than the column it is about.
     */
    private static List<String> carriers(DSLContext dsl, String typeName) {
        derive(dsl);
        var c = INTENT_CARRIER_DATA_FIELD;
        var partition = c.GRAPH_NAME.eq(GRAPH);
        return dsl.select(c.fields())
            .from(c)
            .where(typeName == null ? partition : partition.and(c.TYPE_NAME.eq(typeName)))
            .orderBy(c.TYPE_NAME, c.FIELD_NAME, c.FAMILY)
            .fetch()
            .map(row -> row.get(c.TYPE_NAME) + "." + row.get(c.FIELD_NAME) + " "
                + row.get(c.FAMILY) + " " + row.get(c.ELEMENT_KIND) + " " + row.get(c.DATA_FIELDS));
    }
}
