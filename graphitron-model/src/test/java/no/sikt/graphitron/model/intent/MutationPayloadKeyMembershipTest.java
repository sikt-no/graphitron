package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUniqueKey;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_mutation_payload_key_membership} states: which of the columns an UPDATE's
 * payload contributes fall inside the key it matched, and how each carrier as a whole falls against
 * that boundary.
 *
 * <p>The substrate the write destination and the write refusal both reduce, and the reason it is a
 * relation of its own rather than a step inside either: the per-column answer and the per-carrier
 * one are needed together, the disposition of a column turning on where its own carrier falls and
 * not only on where it does. It is also what the straddle diagnostic renders, that error carrying
 * exactly the two column lists this relation partitions one carrier into.
 *
 * <p>The cases divide into the three ways a carrier can fall, the column-level answer underneath
 * them, and the population, which is narrower than the write payload's in two directions at once.
 */
class MutationPayloadKeyMembershipTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /** The catalog {@link MutationWriteDestinationTest} partitions over, measured here instead. */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);

            seedTable(dsl, PKG, PUBLIC, "film");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "title", 1, "TITLE");
            seedColumn(dsl, PKG, PUBLIC, "film", "alt_code", 2, "ALT_CODE");
            seedColumn(dsl, PKG, PUBLIC, "film", "parent_id", 3, "PARENT_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_a_ref", 4, "PUB_A_REF");
            seedColumn(dsl, PKG, PUBLIC, "film", "pub_b_ref", 5, "PUB_B_REF");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_alt_uk", "alt_code");

            seedTable(dsl, PKG, PUBLIC, "publisher");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_a", 0, "PUB_A");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_b", 1, "PUB_B");
            seedPrimaryKey(dsl, PKG, PUBLIC, "publisher", "publisher_pkey", "pub_a", "pub_b");

            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_pub_fkey",
                "publisher", "publisher_pkey", "pub_a_ref", "pub_b_ref");

            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            body.accept(dsl);
        });
    }

    private static void updateSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
        seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
        seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", inputTypeName);
    }

    private static void deleteSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
        seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
        seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "in", inputTypeName);
    }

    private static void payloadField(DSLContext dsl, String mutationField, String inputTypeName,
                                     String fieldName, String namedType, int ordinal) {
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, namedType, ordinal, true, false, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", mutationField, "in", inputTypeName,
            new OccurrenceStep(inputTypeName, fieldName, namedType));
    }

    /**
     * The two-column reference to {@code publisher}. Which key claims those two columns is left to
     * the case: a candidate over both makes the carrier whole and one over the first alone makes it
     * straddle, and a catalog carrying both would always match the narrower one first.
     */
    private static void crossTableFkField(DSLContext dsl, String mutationField, String inputTypeName,
                                          String fieldName, int ordinal) {
        seedType(dsl, GRAPH, "Publisher", "OBJECT");
        seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
        seedNode(dsl, GRAPH, "Publisher");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");
        payloadField(dsl, mutationField, inputTypeName, fieldName, "ID", ordinal);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Publisher");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_pub_fkey");
    }

    private static List<String> membership(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.fields())
            .from(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP)
            .where(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.PATH,
                     INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.POSITION)
            .fetch()
            .map(MutationPayloadKeyMembershipTest::render);
    }

    /**
     * The occurrence and slot, the column, whether that column is in the key, and how the carrier
     * as a whole falls. The last two are the whole content of the relation and they disagree on a
     * straddler by design, which is what the two consumers fork on.
     */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.PATH) + " "
            + row.get(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.POSITION) + ":"
            + row.get(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.COLUMN_NAME) + " "
            + (row.get(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.IN_KEY) ? "in" : "out") + " "
            + row.get(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.CARRIER_KEY_MEMBERSHIP) + " "
            + row.get(INTENT_MUTATION_PAYLOAD_KEY_MEMBERSHIP.CONSTRAINT_NAME);
    }

    // ===== The three ways a carrier falls =====

    /**
     * A single-column carrier falls wholly on one side or the other, and the key it is measured
     * against is named on the row: the one the match picked, not the primary key by default.
     */
    @Test
    void aSingleColumnCarrierIsWhollyInsideOrWhollyOutside() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(membership(dsl)).containsExactly(
                "Mutation.updateFilm(in)/film_id 0:film_id in WHOLE film_pkey",
                "Mutation.updateFilm(in)/title 0:title out NONE film_pkey");
        });
    }

    /**
     * A multi-column carrier every column of which is in the key falls wholly inside it. The
     * carrier-level answer is not a majority of its columns but an all-of, which is what makes the
     * remaining case a straddle rather than a lean.
     */
    @Test
    void aCarrierWhoseEveryColumnIsInTheKeyFallsWhollyInside() {
        withCatalog(dsl -> {
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_pub_pair_uk", "pub_a_ref", "pub_b_ref");
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(membership(dsl)).containsExactly(
                "Mutation.updateFilm(in)/publisherRef 0:pub_a_ref in WHOLE film_pub_pair_uk",
                "Mutation.updateFilm(in)/publisherRef 1:pub_b_ref in WHOLE film_pub_pair_uk",
                "Mutation.updateFilm(in)/title 0:title out NONE film_pub_pair_uk");
        });
    }

    /**
     * A carrier with a column on each side of the boundary straddles it. The per-column answers
     * differ within one carrier, and the carrier-level answer repeated beside them is what says
     * that this is the one carrier shape whose columns may be dispositioned apart.
     */
    @Test
    void aCarrierWithAColumnOnEachSideStraddles() {
        withCatalog(dsl -> {
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_pub_a_uk", "pub_a_ref");
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(membership(dsl)).containsExactly(
                "Mutation.updateFilm(in)/publisherRef 0:pub_a_ref in STRADDLE film_pub_a_uk",
                "Mutation.updateFilm(in)/publisherRef 1:pub_b_ref out STRADDLE film_pub_a_uk",
                "Mutation.updateFilm(in)/title 0:title out NONE film_pub_a_uk");
        });
    }

    // ===== The population =====

    /**
     * A DELETE has no row here. Its matched key is a cardinality guard rather than a partition, so
     * asking which of its columns fall inside that key answers a question no consumer of a DELETE
     * asks, and a relation that answered it anyway would invite one to be read into the statement.
     */
    @Test
    void aDeleteHasNoMembership() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "film_id", "String", 0);

            assertThat(membership(dsl)).isEmpty();
        });
    }

    /**
     * An UPDATE that pins no key has no row here either. There is no boundary to measure against,
     * and the refusal that follows is the matched key's rather than a membership of its own.
     */
    @Test
    void anUncoveredUpdateHasNoMembership() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 0);

            assertThat(membership(dsl)).isEmpty();
        });
    }

    /**
     * A payload one of whose fields the walker refuses has no row here at all, refused field or
     * not. The refusal is collected before any key is matched, so nothing downstream of the match
     * exists for the admitted remainder either.
     */
    @Test
    void aPayloadWithARefusedFieldHasNoMembership() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            seedInputField(dsl, GRAPH, "FilmUpdateInput", "notAColumn", "String", 1,
                false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmUpdateInput",
                new OccurrenceStep("FilmUpdateInput", "notAColumn", "String"));

            assertThat(membership(dsl)).isEmpty();
        });
    }
}
