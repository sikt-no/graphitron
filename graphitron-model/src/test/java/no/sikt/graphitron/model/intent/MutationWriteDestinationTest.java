package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_WRITE_DESTINATION;
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
 * What {@code intent_mutation_write_destination} states: what each column a write payload
 * contributes is for, once the matched key has partitioned it.
 *
 * <p>Three destinations and the cases divide by which of them a column reaches. A DELETE reaches
 * only one, every admitted column being a predicate and the key a cardinality guard beside them.
 * An UPDATE reaches all three, and which one a column reaches turns on its carrier rather than on
 * the column: a carrier wholly inside the key filters, one wholly outside it writes, a
 * self-referencing foreign key writes however it falls, and a cross-table foreign key is the one
 * carrier that splits, its in-key half filtering and its out-of-key half writing.
 *
 * <p>The third destination is the one that is easy to miss. Where two carriers both decode a value
 * for one key column, only one of them supplies the predicate and the other is checked against it
 * at runtime and neither filtered nor written. That is a column with a contribution and no
 * destination in the statement, and it is stated here rather than left as an absence.
 */
class MutationWriteDestinationTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * A {@code film} table whose candidate keys are ranked so that each case can steer the match to
     * the key it needs: the primary key on {@code film_id}, then unique keys on {@code alt_code},
     * on {@code parent_id} and on {@code pub_a_ref}. Two foreign keys leave from it, one back to
     * {@code film} itself and one to {@code publisher} lifting two columns of which a unique key
     * claims exactly one, which is what makes a straddle expressible at all.
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
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_pub_a_uk", "pub_a_ref");

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

    /** One nullable input field on a payload type, reached from the named write surface. */
    private static void payloadField(DSLContext dsl, String mutationField, String inputTypeName,
                                     String fieldName, String namedType, int ordinal) {
        payloadField(dsl, mutationField, inputTypeName, fieldName, namedType, ordinal, false);
    }

    /** The same, with the field's non-nullness stated, which one straddle case turns on. */
    private static void payloadField(DSLContext dsl, String mutationField, String inputTypeName,
                                     String fieldName, String namedType, int ordinal,
                                     boolean nonNull) {
        seedInputField(dsl, GRAPH, inputTypeName, fieldName, namedType, ordinal, nonNull, false, null);
        seedOccurrencePath(dsl, GRAPH, "Mutation", mutationField, "in", inputTypeName,
            new OccurrenceStep(inputTypeName, fieldName, namedType));
    }

    /** {@code Film} as a node type of its own table, keyed on its primary key column. */
    private static void filmIsANode(DSLContext dsl) {
        seedNode(dsl, GRAPH, "Film");
        seedNodeKeyColumnRef(dsl, GRAPH, "Film", 0, "film_id");
    }

    /**
     * A {@code @nodeId(typeName: Film) @reference} following the self-referencing foreign key: one
     * column, {@code parent_id}, pointing at a sibling row rather than at this one.
     */
    private static void selfFkField(DSLContext dsl, String mutationField, String inputTypeName,
                                    String fieldName, int ordinal) {
        filmIsANode(dsl);
        payloadField(dsl, mutationField, inputTypeName, fieldName, "ID", ordinal);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Film");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_parent_fkey");
    }

    /**
     * A {@code @nodeId(typeName: Publisher) @reference} following the foreign key to
     * {@code publisher}: two lifted columns, {@code pub_a_ref} and {@code pub_b_ref}, of which the
     * {@code film_pub_a_uk} unique key claims the first.
     */
    private static void crossTableFkField(DSLContext dsl, String mutationField, String inputTypeName,
                                          String fieldName, int ordinal, boolean nonNull) {
        seedType(dsl, GRAPH, "Publisher", "OBJECT");
        seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
        seedNode(dsl, GRAPH, "Publisher");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
        seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");
        payloadField(dsl, mutationField, inputTypeName, fieldName, "ID", ordinal, nonNull);
        seedFieldNodeId(dsl, GRAPH, inputTypeName, fieldName, "Publisher");
        seedFieldReference(dsl, GRAPH, inputTypeName, fieldName, 0);
        seedFieldReferenceStep(dsl, GRAPH, inputTypeName, fieldName, 0, 0, null, "film_pub_fkey");
    }

    private static List<String> destinations(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_MUTATION_WRITE_DESTINATION.fields())
            .from(INTENT_MUTATION_WRITE_DESTINATION)
            .where(INTENT_MUTATION_WRITE_DESTINATION.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_WRITE_DESTINATION.PATH,
                     INTENT_MUTATION_WRITE_DESTINATION.POSITION)
            .fetch()
            .map(MutationWriteDestinationTest::render);
    }

    /**
     * What an explicit null on each carrier means, at the grain the answer is decided: one row per
     * occurrence, read off the rows this relation already carries. Separate from {@link #render}
     * because the fact is the carrier's and not the column's, so a per-column rendering would repeat
     * one answer down N rows with nothing able to see them disagree.
     */
    private static List<String> nullRules(DSLContext dsl) {
        derive(dsl);
        return dsl.selectDistinct(INTENT_MUTATION_WRITE_DESTINATION.PATH,
                                  INTENT_MUTATION_WRITE_DESTINATION.ON_EXPLICIT_NULL)
            .from(INTENT_MUTATION_WRITE_DESTINATION)
            .where(INTENT_MUTATION_WRITE_DESTINATION.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_MUTATION_WRITE_DESTINATION.PATH)
            .fetch()
            .map(r -> r.get(INTENT_MUTATION_WRITE_DESTINATION.PATH) + " -> "
                + r.get(INTENT_MUTATION_WRITE_DESTINATION.ON_EXPLICIT_NULL));
    }

    /**
     * The occurrence, what its carrier points at, the decode slot, the column it binds and where
     * that column goes. The slot is spelled out because the split is per column and a consumer
     * reading one half of a split carrier cannot recover the slot from that half's own ordering.
     */
    private static String render(Record row) {
        return row.get(INTENT_MUTATION_WRITE_DESTINATION.PATH) + " "
            + row.get(INTENT_MUTATION_WRITE_DESTINATION.CARRIER_ROLE) + " "
            + row.get(INTENT_MUTATION_WRITE_DESTINATION.POSITION) + ":"
            + row.get(INTENT_MUTATION_WRITE_DESTINATION.COLUMN_NAME) + " -> "
            + row.get(INTENT_MUTATION_WRITE_DESTINATION.DESTINATION);
    }

    // ===== The two halves of an ordinary UPDATE =====

    /**
     * The base case, and the partition in one payload: the carrier whose columns are the matched
     * key filters, and every other carrier writes.
     */
    @Test
    void aKeyCarrierFiltersAndTheRestWrite() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "alt_code", "String", 2);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateFilm(in)/alt_code OWN_COLUMNS 0:alt_code -> VALUE",
                "Mutation.updateFilm(in)/film_id OWN_COLUMNS 0:film_id -> PREDICATE",
                "Mutation.updateFilm(in)/title OWN_COLUMNS 0:title -> VALUE");
        });
    }

    /**
     * The partition is against the key the match named rather than against the primary key. With
     * the primary key uncovered the match walks down the ranking, and the column that filters is
     * the one the winning candidate is declared on.
     */
    @Test
    void thePartitionFollowsTheKeyTheMatchNamed() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "alt_code", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateFilm(in)/alt_code OWN_COLUMNS 0:alt_code -> PREDICATE",
                "Mutation.updateFilm(in)/title OWN_COLUMNS 0:title -> VALUE");
        });
    }

    // ===== The carrier that writes however it falls =====

    /**
     * A self-referencing foreign key writes its column even where that column is the one the
     * statement filters on. The columns point at a sibling row, so they are a value this row
     * carries rather than the identity it is found by, and the predicate comes from the plain
     * carrier that supplies the same column.
     */
    @Test
    void aSelfFkWritesAKeyColumnAnotherCarrierFiltersOn() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parent_id", "String", 0);
            selfFkField(dsl, "updateFilm", "FilmUpdateInput", "parentRef", 1);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 2);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateFilm(in)/parentRef SELF_FK 0:parent_id -> VALUE",
                "Mutation.updateFilm(in)/parent_id OWN_COLUMNS 0:parent_id -> PREDICATE",
                "Mutation.updateFilm(in)/title OWN_COLUMNS 0:title -> VALUE");
        });
    }

    // ===== The carrier that splits =====

    /**
     * A cross-table foreign key is the one carrier a column of which can filter while another
     * writes. Its lifted tuple is a pointer at another table's row, and the half of that pointer
     * this table declares a unique key on is this row's own identity.
     */
    @Test
    void aCrossTableFkSplitsAcrossTheKeyBoundary() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 0, true);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateFilm(in)/publisherRef CROSS_TABLE_FK 0:pub_a_ref -> PREDICATE",
                "Mutation.updateFilm(in)/publisherRef CROSS_TABLE_FK 1:pub_b_ref -> VALUE",
                "Mutation.updateFilm(in)/title OWN_COLUMNS 0:title -> VALUE");
        });
    }

    /**
     * The straddler supplies the predicate only where nothing else does. With a plain carrier on
     * the same key column, that carrier filters and the straddler's in-key half is neither
     * filtered on nor written: it is checked against the predicate at runtime, which is the third
     * destination and the reason the vocabulary is not two values.
     */
    @Test
    void aStraddledKeyColumnAnotherCarrierPinsIsCheckedRatherThanFiltered() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "pub_a_ref", "String", 0);
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 1, true);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateFilm(in)/pub_a_ref OWN_COLUMNS 0:pub_a_ref -> PREDICATE",
                "Mutation.updateFilm(in)/publisherRef CROSS_TABLE_FK 0:pub_a_ref -> CHECKED",
                "Mutation.updateFilm(in)/publisherRef CROSS_TABLE_FK 1:pub_b_ref -> VALUE");
        });
    }

    /**
     * Where two straddlers claim one key column and nothing else pins it, the one declared first
     * supplies the predicate and the other is checked. The choice does not change what the
     * statement means, both values being compared before it runs, but it decides which field's
     * decode the emitted predicate reads, so it is the walker's order transcribed rather than
     * whatever order a row arrives in.
     */
    @Test
    void theFirstDeclaredOfTwoContendingStraddlersSuppliesThePredicate() {
        withContendingStraddlers(dsl -> {
            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateShelf(in)/refOne CROSS_TABLE_FK 0:a -> PREDICATE",
                "Mutation.updateShelf(in)/refOne CROSS_TABLE_FK 1:b -> VALUE",
                "Mutation.updateShelf(in)/refTwo CROSS_TABLE_FK 0:a -> CHECKED",
                "Mutation.updateShelf(in)/refTwo CROSS_TABLE_FK 1:d -> VALUE");
        }, "refOne", "refTwo");
    }

    /**
     * The mirror of the pair above, with the two fields declared the other way round. Without it
     * the case above would pass on any order this relation happened to produce.
     */
    @Test
    void reversingTheDeclarationOrderMovesThePredicate() {
        withContendingStraddlers(dsl -> {
            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateShelf(in)/refOne CROSS_TABLE_FK 0:a -> CHECKED",
                "Mutation.updateShelf(in)/refOne CROSS_TABLE_FK 1:b -> VALUE",
                "Mutation.updateShelf(in)/refTwo CROSS_TABLE_FK 0:a -> PREDICATE",
                "Mutation.updateShelf(in)/refTwo CROSS_TABLE_FK 1:d -> VALUE");
        }, "refTwo", "refOne");
    }

    /**
     * A {@code shelf} table keyed on {@code a} alone, with two foreign keys to {@code publisher}
     * lifting {@code a} with {@code b} and {@code a} with {@code d}. Both references straddle the
     * key, both claim {@code a}, and nothing else supplies it, which is the only shape where the
     * walker's input-field order is observable. {@code declared} names the two payload fields in
     * the order they are declared in.
     */
    private static void withContendingStraddlers(Consumer<DSLContext> body, String... declared) {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "shelf");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "a", 0, "A");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "b", 1, "B");
            seedColumn(dsl, PKG, PUBLIC, "shelf", "d", 2, "D");
            seedPrimaryKey(dsl, PKG, PUBLIC, "shelf", "shelf_pkey", "a");
            seedForeignKey(dsl, PKG, PUBLIC, "shelf", "shelf_one_fkey",
                "publisher", "publisher_pkey", "a", "b");
            seedForeignKey(dsl, PKG, PUBLIC, "shelf", "shelf_two_fkey",
                "publisher", "publisher_pkey", "a", "d");
            seedTableBinding(dsl, GRAPH, "Shelf", "shelf");
            seedType(dsl, GRAPH, "Publisher", "OBJECT");
            seedTableBinding(dsl, GRAPH, "Publisher", "publisher");
            seedNode(dsl, GRAPH, "Publisher");
            seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 0, "pub_a");
            seedNodeKeyColumnRef(dsl, GRAPH, "Publisher", 1, "pub_b");

            seedType(dsl, GRAPH, "ShelfUpdateInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "updateShelf", "Shelf", false);
            seedMutation(dsl, GRAPH, "Mutation", "updateShelf", "UPDATE");
            seedArgument(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput");

            for (int ordinal = 0; ordinal < declared.length; ordinal++) {
                var name = declared[ordinal];
                var key = name.equals("refOne") ? "shelf_one_fkey" : "shelf_two_fkey";
                seedInputField(dsl, GRAPH, "ShelfUpdateInput", name, "ID", ordinal, true, false, null);
                seedOccurrencePath(dsl, GRAPH, "Mutation", "updateShelf", "in", "ShelfUpdateInput",
                    new OccurrenceStep("ShelfUpdateInput", name, "ID"));
                seedFieldNodeId(dsl, GRAPH, "ShelfUpdateInput", name, "Publisher");
                seedFieldReference(dsl, GRAPH, "ShelfUpdateInput", name, 0);
                seedFieldReferenceStep(dsl, GRAPH, "ShelfUpdateInput", name, 0, 0, null, key);
            }
            body.accept(dsl);
        });
    }

    // ===== DELETE reaches one destination only =====

    /**
     * Every admitted column of a DELETE is a predicate, the carrier's kind notwithstanding. The
     * matched key is a cardinality guard rather than a partition, so a self-referencing foreign
     * key filters here exactly as a plain column does, which is the opposite of what it does under
     * UPDATE and the reason the verb is on the row.
     */
    @Test
    void everyDeleteColumnIsAPredicate() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "film_id", "String", 0);
            selfFkField(dsl, "deleteFilm", "FilmDeleteInput", "parentRef", 1);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.deleteFilm(in)/film_id OWN_COLUMNS 0:film_id -> PREDICATE",
                "Mutation.deleteFilm(in)/parentRef SELF_FK 0:parent_id -> PREDICATE");
        });
    }

    // ===== What has no destination at all =====

    /**
     * A payload the walker refuses contributes nothing, whatever the refusal was: the statement is
     * not emitted, so no column of it has a destination. Here an UPDATE naming only its primary key
     * has nothing to set, which is the empty-assignment refusal, and the key column that would
     * otherwise filter has no row either.
     */
    @Test
    void aRefusedPayloadContributesNoDestination() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);

            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * An UPDATE whose input pins no key is a refusal before the partition exists, so it has no
     * destination rows either. The absence is the matched key's to explain and not this relation's.
     */
    @Test
    void anUncoveredUpdateContributesNoDestination() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 0);

            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * A DELETE that covers no key and did not opt into the broadcast reading contributes nothing.
     * Its input cannot identify what to delete, which is an author error rather than a statement,
     * and the columns of a statement that is never emitted have nowhere to go.
     */
    @Test
    void anUncoveredSingleRowDeleteContributesNoDestination() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "title", "String", 0);

            assertThat(destinations(dsl)).isEmpty();
        });
    }

    /**
     * A DELETE that covers no key and opts into the broadcast reading still contributes every one
     * of its columns as a predicate. There is no key to partition around, which is what the arm
     * means, and the columns are the filter the statement runs on.
     */
    @Test
    void aBroadcastDeleteStillContributesPredicates() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "title", "String", 0);
            dsl.update(GRAPHITRON_MUTATION)
                .set(GRAPHITRON_MUTATION.MULTI_ROW, true)
                .where(GRAPHITRON_MUTATION.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHITRON_MUTATION.FIELD_NAME.eq("deleteFilm"))
                .execute();

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.deleteFilm(in)/title OWN_COLUMNS 0:title -> PREDICATE");
        });
    }

    // ===== The grain =====

    /**
     * Two carriers writing one column are two rows and not one. The destination is a property of
     * the occurrence rather than of the column, which is what lets a consumer emit one assignment
     * per contributing field. The pair here is admitted because one of the two decodes its value,
     * the collision refusal being about two plain writers alone.
     */
    @Test
    void twoCarriersWritingOneColumnAreTwoRows() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            selfFkField(dsl, "updateFilm", "FilmUpdateInput", "parentRef", 1);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parent_id", "String", 2);

            assertThat(destinations(dsl)).containsExactly(
                "Mutation.updateFilm(in)/film_id OWN_COLUMNS 0:film_id -> PREDICATE",
                "Mutation.updateFilm(in)/parentRef SELF_FK 0:parent_id -> VALUE",
                "Mutation.updateFilm(in)/parent_id OWN_COLUMNS 0:parent_id -> VALUE");
        });
    }

    // ===== What an explicit null means =====

    /**
     * The three answers on one payload, which is what makes them a vocabulary rather than a flag.
     * {@code film_id} assigns nothing, being the whole key, so it holds no rule at all: an absence
     * rather than a fourth value. {@code title} is nullable and assigns a column outside the key, so
     * a null clears it. The non-null {@code publisherRef} cannot receive one, the schema refusing it
     * before the statement is reached.
     */
    @Test
    void theThreeAnswersAndTheAbsence() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "film_id", "String", 0);
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "title", "String", 1);
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 2, true);

            assertThat(nullRules(dsl)).containsExactly(
                "Mutation.updateFilm(in)/film_id -> null",
                "Mutation.updateFilm(in)/publisherRef -> CANNOT_ARRIVE",
                "Mutation.updateFilm(in)/title -> CLEARS");
        });
    }

    /**
     * The answer turns on what the carrier <em>assigns</em> and never on whether it straddles, which
     * is what makes the self-FK case fall out rather than needing its own clause. A nullable
     * self-referencing foreign key routes {@code parent_id} to the assignment half, and here that
     * column is also the matched key's, so clearing it would leave the row without an identity and
     * the null is refused when the statement runs. The straddling reference beside it is admitted and
     * clears, its assigned half being its out-of-key half by construction.
     */
    @Test
    void aNullableCarrierAssigningAKeyColumnIsRefusedAndAStraddlerIsNot() {
        withCatalog(dsl -> {
            updateSurface(dsl, "FilmUpdateInput");
            payloadField(dsl, "updateFilm", "FilmUpdateInput", "parent_id", "String", 0);
            selfFkField(dsl, "updateFilm", "FilmUpdateInput", "parentRef", 1);
            crossTableFkField(dsl, "updateFilm", "FilmUpdateInput", "publisherRef", 2, false);

            assertThat(nullRules(dsl)).containsExactly(
                "Mutation.updateFilm(in)/parentRef -> REFUSED_AS_IDENTITY",
                "Mutation.updateFilm(in)/parent_id -> null",
                "Mutation.updateFilm(in)/publisherRef -> CLEARS");
        });
    }

    /** A DELETE assigns nothing at all, so no row of one carries a rule. */
    @Test
    void aDeleteCarriesNoNullRule() {
        withCatalog(dsl -> {
            deleteSurface(dsl, "FilmDeleteInput");
            payloadField(dsl, "deleteFilm", "FilmDeleteInput", "film_id", "String", 0);

            assertThat(nullRules(dsl)).containsExactly("Mutation.deleteFilm(in)/film_id -> null");
        });
    }
}
