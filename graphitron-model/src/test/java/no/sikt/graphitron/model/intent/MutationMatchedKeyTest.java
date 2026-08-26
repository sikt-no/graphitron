package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_MATCHED_KEY;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
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
 * What {@code intent_mutation_matched_key} states: whether a write payload pins the row it acts on,
 * and through which of the write table's candidate keys.
 *
 * <p>The load-bearing pair is the verb pair. The covered set an UPDATE matches over excludes the
 * columns of a self-referencing foreign key and a DELETE's does not, so one payload over one table
 * answers differently under the two verbs; both halves of that pair are stated here over the same
 * fixture, because either half alone reads as a fact about the input rather than about the verb.
 *
 * <p>The rest of the cases divide by what the verdict turns on: which candidate key wins and how far
 * down the ranking the match had to go, and what happens when none is covered, which is a broadcast
 * on one verb and an author error on the other.
 */
class MutationMatchedKeyTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * A {@code film} table with three candidate keys in a known order: the primary key on
     * {@code film_id}, then a unique key on {@code alt_code}, then one on {@code parent_id}. That
     * last column is also a foreign key back to {@code film}, which is what lets one payload pin a
     * key under DELETE and pin nothing under UPDATE.
     */
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
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_parent_uk", "parent_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_pub_uk", "pub_a_ref", "pub_b_ref");

            seedTable(dsl, PKG, PUBLIC, "publisher");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_a", 0, "PUB_A");
            seedColumn(dsl, PKG, PUBLIC, "publisher", "pub_b", 1, "PUB_B");
            seedPrimaryKey(dsl, PKG, PUBLIC, "publisher", "publisher_pkey", "pub_a", "pub_b");

            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_parent_fkey",
                "film", "film_pkey", "parent_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_pub_fkey",
                "publisher", "publisher_pkey", "pub_a_ref", "pub_b_ref");

            seedType(dsl, GRAPH, "String", "SCALAR");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            body.accept(dsl);
        });
    }

    /** An UPDATE write surface over {@code film}, taking its payload through {@code in}. */
    private static void updateSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
        seedMutation(dsl, GRAPH, "Mutation", "updateFilm", "UPDATE");
        seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", inputTypeName);
    }

    /** A DELETE write surface over the same table, which names it on the field. */
    private static void deleteSurface(DSLContext dsl, String inputTypeName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
        seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
        seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "in", inputTypeName);
    }

    /** One input field on a payload type, reached from the named write surface's argument. */
    private static void payloadField(DSLContext dsl, String mutationField, String inputTypeName,
                                     String fieldName, String namedType, int ordinal) {
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, namedType, ordinal, false, false, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", mutationField, "in", inputTypeName,
            new OccurrenceStep(inputTypeName, fieldName, namedType));
    }

    /** {@code @mutation(multiRow: true)}, which the seeding helper leaves unstated. */
    private static void seedMultiRow(DSLContext dsl, String fieldName) {
        dsl.update(GRAPHITRON_MUTATION)
            .set(GRAPHITRON_MUTATION.MULTI_ROW, true)
            .where(GRAPHITRON_MUTATION.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHITRON_MUTATION.FIELD_NAME.eq(fieldName))
            .execute();
    }

    /** {@code Film} as a node type of its own table, keyed on its primary key column. */
    private static void filmIsANode(DSLContext dsl) {
        seedNode(dsl, GRAPH, "Film");
        seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
    }

    /**
     * A {@code @nodeId(typeName: Film)} field following the self-referencing foreign key, whose one
     * column is the {@code parent_id} that a unique key is declared on.
     */
    private static void selfFkField(DSLContext dsl, String mutationField, String inputTypeName,
                                    String fieldName, int ordinal) {
        filmIsANode(dsl);
        payloadField(dsl, mutationField, inputTypeName, fieldName, "ID", ordinal);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Film");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_parent_fkey");
    }

    private static List<String> verdicts(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_MATCHED_KEY.fields())
            .from(INTENT_MUTATION_MATCHED_KEY)
            .where(INTENT_MUTATION_MATCHED_KEY.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_MATCHED_KEY.FIELD_NAME)
            .fetch()
            .map(MutationMatchedKeyTest::render);
    }

    /**
     * The coordinate, the verb, the verdict and the key it named, with the rank and the primary flag
     * spelled out: every column of the row, because each is a claim some case here makes.
     */
    private static String render(Record row) {
        Boolean primary = row.get(INTENT_MUTATION_MATCHED_KEY.PRIMARY_KEY);
        Integer rank = row.get(INTENT_MUTATION_MATCHED_KEY.CANDIDATE_RANK);
        return row.get(INTENT_MUTATION_MATCHED_KEY.FIELD_NAME) + " "
            + row.get(INTENT_MUTATION_MATCHED_KEY.OPERATION) + " "
            + row.get(INTENT_MUTATION_MATCHED_KEY.WRITE_TABLE) + " "
            + row.get(INTENT_MUTATION_MATCHED_KEY.VERDICT) + " "
            + row.get(INTENT_MUTATION_MATCHED_KEY.CONSTRAINT_NAME) + " "
            + (primary == null ? "null" : primary ? "pk" : "uk") + " "
            + (rank == null ? "null" : "rank" + rank);
    }

    // ===== Which key wins =====

    /** An input naming the primary key column pins the primary key, at the top of the ranking. */
    @Test
    void anInputCoveringThePrimaryKeyIsIdentified() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(verdicts(dsl)).containsExactly(
                "updateFilm UPDATE film IDENTIFIED film_pkey pk rank0");
        });
    }

    /**
     * An input covering both the primary key and a unique key takes the primary key. Not a
     * preference this relation invented: the candidate ranking puts the primary key first, and this
     * relation reads that order ascending.
     */
    @Test
    void thePrimaryKeyOutranksACoveredUniqueKey() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "alt_code", "String", 1);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 2);

            assertThat(verdicts(dsl)).containsExactly(
                "updateFilm UPDATE film IDENTIFIED film_pkey pk rank0");
        });
    }

    /**
     * With the primary key uncovered, the match walks down the ranking to the first unique key the
     * input does cover, and the rank on the row says how far it went.
     */
    @Test
    void aUniqueKeyWinsWhereThePrimaryIsNotCovered() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "alt_code", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(verdicts(dsl)).containsExactly(
                "updateFilm UPDATE film IDENTIFIED film_alt_uk uk rank1");
        });
    }

    /**
     * A compound key is covered only when every column of it is, which a two-slot decode supplies in
     * one contribution. Coverage is a subset test over columns rather than a match against a
     * carrier, so a key spanning two columns is pinned by one input field.
     */
    @Test
    void aCompoundKeyIsCoveredByOneDecodedContribution() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Publisher", "OBJECT");
            seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
            seedNode(dsl, GRAPH, "Publisher");
            seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
            seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "publisherId", "ID", 0);
            seedFieldNodeId(dsl, GRAPH, "FilmUpdateInput", "publisherId", "Publisher");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(verdicts(dsl)).containsExactly(
                "updateFilm UPDATE film IDENTIFIED film_pub_uk uk rank3");
        });
    }

    // ===== The verb pair, which is the whole reason the verb is on the row =====

    /**
     * A DELETE whose only key-covering column arrives through a self-referencing foreign key is
     * identified: every admitted column of a DELETE is a WHERE predicate, so the column pins the row
     * whatever it points at.
     */
    @Test
    void aDeleteCountsASelfFkColumnTowardCoverage() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            selfFkField(dsl, "deleteFilm", "FilmDeleteInput", "parentId", 0);

            assertThat(verdicts(dsl)).containsExactly(
                "deleteFilm DELETE film IDENTIFIED film_parent_uk uk rank2");
        });
    }

    /**
     * The same payload under UPDATE pins nothing. A self-FK's column holds a sibling row's identity,
     * so keying the UPDATE on it would update the wrong row, and the walker excludes such columns
     * from the covered set before matching. The half of the verb pair that is easy to get wrong by
     * reading the columns alone.
     */
    @Test
    void anUpdateDoesNotCountASelfFkColumnTowardCoverage() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            selfFkField(dsl, "updateFilm", "FilmUpdateInput", "parentId", 0);

            assertThat(verdicts(dsl)).containsExactly(
                "updateFilm UPDATE film UNCOVERED null null null");
        });
    }

    // ===== Covering nothing =====

    /**
     * A DELETE covering no key and opting into {@code multiRow: true} is a broadcast: the author has
     * said the statement may match many rows, and the emitted statement carries no key predicate.
     */
    @Test
    void aMultiRowDeleteCoveringNoKeyBroadcasts() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            seedMultiRow(dsl, "deleteFilm");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "title", "String", 0);

            assertThat(verdicts(dsl)).containsExactly(
                "deleteFilm DELETE film BROADCAST null null null");
        });
    }

    /** The same DELETE without the opt-in is an author error rather than a broadcast. */
    @Test
    void aSingleRowDeleteCoveringNoKeyIsUncovered() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "title", "String", 0);

            assertThat(verdicts(dsl)).containsExactly(
                "deleteFilm DELETE film UNCOVERED null null null");
        });
    }

    /**
     * An UPDATE covering no key is uncovered and can be nothing else. The broadcast reading has no
     * UPDATE spelling at all: {@code multiRow: true} on an UPDATE is refused before a write surface
     * exists, so the arm is unreachable rather than merely unused.
     */
    @Test
    void anUpdateCoveringNoKeyIsUncovered() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 0);

            assertThat(verdicts(dsl)).containsExactly(
                "updateFilm UPDATE film UNCOVERED null null null");
        });
    }

    // ===== The population =====

    /**
     * A payload with a refused field has no verdict at all. Both walkers collect every per-field
     * refusal and return before the match, on the grounds that an unadmitted field makes the covered
     * set unreliable; a coverage verdict here would be one the build never computed, and it would be
     * wrong in the direction that reads as an author error on top of a real one.
     */
    @Test
    void aPayloadWithARefusedFieldHasNoVerdict() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);
            seedFieldCondition(dsl, GRAPH, "FilmUpdateInput", "title", false);

            assertThat(verdicts(dsl)).isEmpty();
        });
    }

    /** A field that is no write surface at all has no verdict, the population being that relation's. */
    @Test
    void anInsertHasNoVerdict() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "FilmCreateInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedMutation(dsl, GRAPH, "Mutation", "createFilm", "INSERT");
            seedArgument(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmCreateInput");
            payloadField(dsl, "createFilm", "FilmCreateInput", "film_id", "String", 0);

            assertThat(verdicts(dsl)).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph reads none of this one's verdicts. */
    @Test
    void aSiblingGraphReadsNoVerdicts() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);

            derive(dsl);
            assertThat(dsl.selectFrom(INTENT_MUTATION_MATCHED_KEY)
                .where(INTENT_MUTATION_MATCHED_KEY.GRAPH_NAME.eq("other"))
                .fetch()).isEmpty();
        });
    }
}
