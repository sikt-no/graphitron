package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CARRIER_ROUTINE_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_NAME_MATCHED_KEY_PAIR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.upper;

/**
 * The anchor for the two relations behind a hop that leaves a table-valued function's result:
 * {@code intent_name_matched_key_pair}, which states how such a hop is keyed, and
 * {@code intent_carrier_routine_hop}, the population that reaches that rule from a coordinate no
 * author wrote a path at.
 *
 * <p>The pairing relation is catalog only, so its cases assert against the test catalog's own
 * functions and tables and never scope by graph: {@code films_for_actor} exposes {@code film_id} and
 * {@code title}, which is a whole key for {@code film}, none of {@code actor}'s, and half of
 * {@code film_actor}'s two-column one. Half the cases pin what the relation refuses to hold, because
 * the boundary of a rule that pairs every function against every keyed table is most of what it
 * claims.
 *
 * <p>The carrier cases capture SDL, because which two tables that hop connects is a question about a
 * schema. They are the reason the pairing is its own relation rather than an arm of the authored hop
 * view: a payload carrier's data field declares no {@code @reference}, so the authored view's
 * population never reaches it, and the two would otherwise each carry a copy of the rule.
 */
@PipelineTier
class NameMatchedKeyPairTest {

    @TempDir
    Path tmp;

    /** No directive reaches the pairing rule, so the SDL behind its cases only has to be a graph. */
    private static final String ANY_GRAPH = """
        type Film @table(name: "film") { title: String }
        type Query { films: [Film] }
        """;

    /** The error channel the carrier fixtures declare, so no payload below is one without one. */
    private static final String ERRORS = """
        type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
        union WriteError = DbErr
        """;

    // ===== The keying rule =====

    /**
     * The whole point of the relation: a function exposing every column of the arrival's key pairs
     * them, and the pairing is total.
     */
    @Test
    void aFunctionExposingTheWholeKeyPairsIt() {
        withCaptured(ANY_GRAPH, dsl -> assertThat(pairs(dsl, "films_for_actor", "film"))
            .containsExactly("0 film_id=film_id unmatched=0"));
    }

    /**
     * A shortfall is a row, not an absence. The generator's diagnostic at either seat has to name
     * the key column the function does not expose, and it reads that name off this row.
     */
    @Test
    void aFunctionMissingAKeyColumnStatesTheGapAsARow() {
        withCaptured(ANY_GRAPH, dsl -> assertThat(pairs(dsl, "films_for_actor", "actor"))
            .containsExactly("0 actor_id=<none> unmatched=1"));
    }

    /**
     * A composite key is a row per column, in the key's own order, and a partial match is neither a
     * pairing nor a nothing: one column pairs, the other does not, and the count says so on both
     * rows so either one answers the totality question alone.
     */
    @Test
    void aCompositeKeyIsARowPerColumnInTheKeysOrder() {
        withCaptured(ANY_GRAPH, dsl -> assertThat(pairs(dsl, "films_for_actor", "film_actor"))
            .containsExactly(
                "0 actor_id=<none> unmatched=1",
                "1 film_id=film_id unmatched=1"));
    }

    /** Only a function result departs: a stored table has foreign keys, and this is not its rule. */
    @Test
    void onlyAFunctionResultDeparts() {
        withCaptured(ANY_GRAPH, dsl -> assertThat(pairs(dsl, "rental", "film")).isEmpty());
    }

    /**
     * A table with no primary key has nothing to name-match, so it is no arrival at all. That is the
     * shortfall the generator reports in the name-match vocabulary rather than in the foreign-key
     * one, and the relation's silence here is what leaves it to be reported.
     */
    @Test
    void aTableWithNoPrimaryKeyIsNoArrival() {
        withCaptured(ANY_GRAPH, dsl -> assertThat(pairs(dsl, "films_for_actor", "film_list")).isEmpty());
    }

    // ===== The carrier's inferred hop =====

    /**
     * The population the authored hop view cannot reach: the data field carries no {@code @reference}
     * and the hop is inferred from the payload's shape, which is why the pairing rule is a relation
     * of its own rather than an arm of a view keyed on the elements an author wrote.
     */
    @Test
    void aRoutineCarrierNamesTheHopItsDataFieldWillTake() {
        withCaptured(ERRORS + """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload {
                rental: Rental
                errors: [WriteError]
            }
            type Query { rentals: [Rental] }
            type Mutation {
                rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """, dsl -> {
            assertThat(carrierHops(dsl))
                .containsExactly("RentFilmPayload.rental rent_film->rental candidates=1");
            assertThat(pairs(dsl, "rent_film", "rental"))
                .containsExactly("0 rental_id=rental_id unmatched=0");
        });
    }

    /**
     * The chained form is the other spelling and it is the authored view's row, not this one's. The
     * producing field carrying {@code @reference} returns the terminus table type rather than a
     * payload, so a row here would name a hop this seat does not take.
     */
    @Test
    void theChainedFormAuthorsItsOwnHopAndIsNotHere() {
        withCaptured("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rentals: [Rental] }
            type Mutation {
                rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                    @reference(path: [{table: "rental"}])
            }
            """, dsl -> {
            assertThat(carrierHops(dsl)).isEmpty();
            assertThat(authoredNameMatchedHops(dsl)).containsExactly("rent_film->rental");
        });
    }

    /** A DML carrier's data field re-reads through the write's own target, not out of a function. */
    @Test
    void aDmlCarrierIsNotThisRelationsPopulation() {
        withCaptured(ERRORS + """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload {
                deletedId: ID
                errors: [WriteError]
            }
            type Query { films: [Film] }
            type Mutation {
                deleteFilm(filmId: Int): DeleteFilmPayload
                    @mutation(typeName: DELETE, table: "film")
            }
            """, dsl -> assertThat(carrierHops(dsl)).isEmpty());
    }

    /**
     * Ambiguity is rows, and this one is real rather than hypothetical: the grounding memo keeps
     * whichever producing field classified first, so a payload two routines return has one of its two
     * departures silently dropped there and both of them stated here.
     */
    @Test
    void twoProducersOfOnePayloadAreTwoCandidates() {
        withCaptured(ERRORS + """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload {
                rental: Rental
                errors: [WriteError]
            }
            type Query { rentals: [Rental] }
            type Mutation {
                rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                    @routine(name: "rent_film",
                             argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                noteFilm(body: String!): RentFilmPayload
                    @routine(name: "create_secure_note", argMapping: "pBody: body")
            }
            """, dsl -> assertThat(carrierHops(dsl)).containsExactly(
                "RentFilmPayload.rental create_secure_note->rental candidates=2",
                "RentFilmPayload.rental rent_film->rental candidates=2"));
    }

    /**
     * The hop is stated even where the pairing comes up short, which is where this relation parts
     * company with the authored arm. That arm enumerates candidate departures out of every function
     * in the graph's sources, so it has to demand a total pairing or it would enumerate all of them;
     * here the departure is the one the producing field names, and a reader diagnosing the refusal
     * needs the hop in hand to say which key column is missing from it.
     */
    @Test
    void aDepartureThatCannotKeyTheArrivalIsStillTheHop() {
        withCaptured(ERRORS + """
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type NotePayload {
                rental: Rental
                errors: [WriteError]
            }
            type Query { rentals: [Rental] }
            type Mutation {
                noteFilm(body: String!): NotePayload
                    @routine(name: "create_secure_note", argMapping: "pBody: body")
            }
            """, dsl -> {
            assertThat(carrierHops(dsl))
                .containsExactly("NotePayload.rental create_secure_note->rental candidates=1");
            assertThat(pairs(dsl, "create_secure_note", "rental"))
                .containsExactly("0 rental_id=<none> unmatched=1");
        });
    }

    // ===== Helpers =====

    /**
     * The pairing between one departure and one arrival, one string per key column: the position,
     * the pair, and the partition's unmatched count. Named case-insensitively because the catalog's
     * own spelling is the relation's, not the fixture's.
     */
    private static List<String> pairs(DSLContext dsl, String fromTable, String toTable) {
        var p = INTENT_NAME_MATCHED_KEY_PAIR;
        return dsl.select(p.fields())
            .from(p)
            .where(nameIs(p.FROM_TABLE, fromTable))
            .and(nameIs(p.TO_TABLE, toTable))
            .orderBy(p.POSITION)
            .fetch()
            .map(row -> row.get(p.POSITION) + " "
                + lower(row.get(p.TO_COLUMN)) + "="
                + (row.get(p.FROM_COLUMN) == null ? "<none>" : lower(row.get(p.FROM_COLUMN)))
                + " unmatched=" + row.get(p.UNMATCHED_COLUMNS));
    }

    /**
     * Every carrier hop the fixture graph holds, one string per row. Asserted whole so a payload that
     * should name no hop cannot hide behind a filter.
     */
    private static List<String> carrierHops(DSLContext dsl) {
        var h = INTENT_CARRIER_ROUTINE_HOP;
        return dsl.select(h.fields())
            .from(h)
            .where(h.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(h.TYPE_NAME, h.FIELD_NAME, h.FROM_TABLE)
            .fetch()
            .map(row -> row.get(h.TYPE_NAME) + "." + row.get(h.FIELD_NAME) + " "
                + lower(row.get(h.FROM_TABLE)) + "->" + lower(row.get(h.TO_TABLE))
                + " candidates=" + row.get(h.CANDIDATES));
    }

    /** The authored view's name-matched rows, so a case can say which of the two relations answered. */
    private static List<String> authoredNameMatchedHops(DSLContext dsl) {
        var s = INTENT_FIELD_REFERENCE_STEP_HOP;
        return dsl.select(s.fields())
            .from(s)
            .where(s.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(s.VIA.eq("NAME_MATCH"))
            .orderBy(s.FROM_TABLE, s.TO_TABLE)
            .fetch()
            .map(row -> lower(row.get(s.FROM_TABLE)) + "->" + lower(row.get(s.TO_TABLE)));
    }

    private static Condition nameIs(org.jooq.Field<String> field, String name) {
        return upper(field).eq(name.toUpperCase(java.util.Locale.ROOT));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()))) {
            body.accept(store.dsl());
        }
    }
}
