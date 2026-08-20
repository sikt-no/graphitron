package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_COLUMN;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
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
 * Where the values a {@code @nodeId} decode yields land: {@code intent_node_id_decode_column}, one row
 * per position of the node type's key, carrying the key column and the column on the slot's own table
 * the value lifts back to.
 *
 * <p>The absent local column is the claim, not the present one. A decode whose every position carries
 * one binds locally on a tuple of the row's own table; one whose positions carry none binds remotely
 * inside a correlated {@code EXISTS}. Those were a single conjunct in the resolution this replaces, so
 * the cases here are organised by whether the lift carries or drops, and every case asserts the key
 * column beside it: a lift that dropped silently and a lift that carried the wrong column read the
 * same if only the local side is checked.
 *
 * <p>The junction chain has a case of its own because it is the shape the old conjunct rejected before
 * the question was reached. Its terminal key's referenced columns <em>are</em> the node type's key
 * columns, so a reading that only compared those would route it to a local predicate over a tuple that
 * does not exist.
 */
class NodeIdDecodeColumnTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== The lift carries =====

    /** Own-row identity: each key column is its own local column, there being nothing to walk. */
    @Test
    void anIdentityDecodeLandsEachKeyColumnOnItself() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");

            assertThat(columns(dsl))
                .containsExactly("Query.films(ids) 0 film_id -> film_id");
        });
    }

    /** A single discovered hop lands the key on the foreign key's own column. */
    @Test
    void aSingleHopLandsTheKeyOnTheForeignKeysOwnColumn() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
            seedField(dsl, GRAPH, "Query", "filmActors", "FilmActor", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "filmActors", "actorId", "Actor");

            assertThat(columns(dsl))
                .containsExactly("Query.filmActors(actorId) 0 actor_id -> actor_id");
        });
    }

    /**
     * An identity-carrying chain: the second hop departs a column the first hop arrived at, so the
     * walk continues and the terminal arrival lifts all the way back to the slot's own table. The
     * regression a change to this walk could plausibly cause, and the case that says it does not.
     */
    @Test
    void anIdentityCarryingChainLiftsBackThroughEveryHop() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedTableBinding(dsl, GRAPH, "CategoryNote", "film_category_note");
            seedField(dsl, GRAPH, "Query", "notes", "CategoryNote", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "notes", "ofFilm", "Film");
            seedArgumentPath(dsl, "Query", "notes", "ofFilm",
                "film_category_note_fkey", "film_category_film_id_fkey");

            assertThat(columns(dsl))
                .containsExactly("Query.notes(ofFilm) 0 film_id -> film_id");
        });
    }

    /**
     * A composite key lands every position, and the case states both so a transposition would show:
     * the pairing is positional within each hop and the terminal arrival is matched to the key column
     * by name, so a key declared in a different order from the node's own list still lands right.
     */
    @Test
    void aCompositeKeyLandsEveryPositionInTheKeysOwnOrder() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmCategory", "film_category");
            seedTableBinding(dsl, GRAPH, "CategoryNote", "film_category_note");
            seedField(dsl, GRAPH, "Query", "notes", "CategoryNote", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "notes", "ofPairing", "FilmCategory");

            assertThat(columns(dsl)).containsExactly(
                "Query.notes(ofPairing) 0 film_id -> film_id",
                "Query.notes(ofPairing) 1 category_id -> category_id");
        });
    }

    // ===== The lift drops =====

    /**
     * The junction chain, which is the whole of site 4b. The terminal hop's referenced columns are the
     * node type's key columns, so the conjunct this relation replaces called it a local binding; the
     * walk says otherwise, because the second hop departs a column the first never arrived at. The key
     * column is still stated, which is what a correlated {@code EXISTS} binds on.
     */
    @Test
    void aJunctionChainLandsNothingLocalAndKeepsItsKeyColumn() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "film_category_category_id_fkey");

            assertThat(columns(dsl))
                .containsExactly("Query.films(inCategory) 0 category_id -> (none)");
        });
    }

    /**
     * A key referencing something other than the node type's key translates a value before it can
     * filter, so no column on the slot's own table holds the decoded one.
     */
    @Test
    void aKeyReferencingAnAlternateKeyLandsNothingLocal() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedTableBinding(dsl, GRAPH, "FilmSequel", "film_sequel");
            seedField(dsl, GRAPH, "Query", "sequels", "FilmSequel", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "sequels", "ofFilm", "Film");
            seedArgumentPath(dsl, "Query", "sequels", "ofFilm", "film_sequel_alt_fkey");

            assertThat(columns(dsl))
                .containsExactly("Query.sequels(ofFilm) 0 film_id -> (none)");
        });
    }

    /**
     * A reverse hop lands whichever key columns the departure happens to share, and no more. Site 4a's
     * shape, and the case that says the walk is not oriented by which end declares the key: the
     * arriving side of the pair is the key's own column here and the departing side is what it
     * references, so a node key column that is also the departure's is reached and the rest is not.
     * Partial, therefore remote, and stated position by position rather than folded into one verdict.
     */
    @Test
    void aReverseHopLandsOnlyTheKeyColumnsTheDepartureShares() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmActor", "film_actor");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "castEntry", "FilmActor");
            seedArgumentPath(dsl, "Query", "films", "castEntry", "film_actor_film_id_fkey");

            assertThat(columns(dsl)).containsExactly(
                "Query.films(castEntry) 0 film_id -> film_id",
                "Query.films(castEntry) 1 actor_id -> (none)");
        });
    }

    /**
     * The reverse hop that shares nothing: the key it arrives on is not the node type's, so no position
     * lands and the whole binding is remote. The sibling of the case above, and the pair is what says
     * the reverse direction is decided by the columns rather than by the direction.
     */
    @Test
    void aReverseHopOntoANonKeyColumnLandsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmSequel", "film_sequel");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "sequelRow", "FilmSequel");
            seedArgumentPath(dsl, "Query", "films", "sequelRow", "film_sequel_alt_fkey");

            assertThat(columns(dsl))
                .containsExactly("Query.films(sequelRow) 0 from_film_id -> (none)");
        });
    }

    /**
     * A partial lift is recorded position by position rather than collapsed. No destination names the
     * case and the reduction above reads anything short of every position as a remote binding, which is
     * always correct; recording it is what lets a diagnostic say which position did not arrive.
     */
    @Test
    void aPartialLiftIsRecordedPositionByPosition() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
            seedNode(dsl, GRAPH, "FilmActor");
            seedNodeKeyColumnRef(dsl, GRAPH, "FilmActor", 0, "film_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "FilmActor", 1, "last_update");
            seedTableBinding(dsl, GRAPH, "CategoryNote", "film_category_note");
            seedField(dsl, GRAPH, "Query", "notes", "CategoryNote", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "notes", "ofCastEntry", "FilmActor");
            seedArgumentPath(dsl, "Query", "notes", "ofCastEntry",
                "film_category_note_film_actor_fkey");

            assertThat(columns(dsl)).containsExactly(
                "Query.notes(ofCastEntry) 0 film_id -> film_id",
                "Query.notes(ofCastEntry) 1 last_update -> (none)");
        });
    }

    /**
     * An input field carrying its own path lands nothing, no relation resolving such a path's terminal
     * yet. Indistinguishable here from a junction chain by the local column alone, which is why the
     * endpoint relation names the shape and this one does not have to.
     */
    @Test
    void anUnresolvedPathLandsNothingLocalAndStillStatesTheKey() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmInput");
            seedType(dsl, GRAPH, "FilmInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "FilmInput", "inCategory", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "FilmInput", "inCategory", "Category");
            seedFieldReference(dsl, GRAPH, "FilmInput", "inCategory", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmInput", "inCategory", 0, 0,
                null, "film_category_film_id_fkey");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmInput",
                new OccurrenceStep("FilmInput", "inCategory", "ID"));

            assertThat(columns(dsl)).containsExactly(
                "Mutation.updateFilm(in)/inCategory 0 category_id -> (none)");
        });
    }

    // ===== The population's own edge =====

    /**
     * A node type no tier resolves key columns for has no rows here at all. That absence is the key
     * relation's own statement and not this one's, which is why it is a case rather than a comment.
     */
    @Test
    void aNodeTypeWithNoResolvedKeyIsNotInThePopulation() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "ledger");
            seedColumn(dsl, PKG, PUBLIC, "ledger", "ledger_id", 0, "LEDGER_ID");
            seedTableBinding(dsl, GRAPH, "Ledger", "ledger");
            seedNode(dsl, GRAPH, "Ledger");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "onLedger", "Ledger");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds. */
    @Test
    void aSiblingGraphLandsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");

            derive(dsl);
            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * A catalog carrying the four shapes the lift turns on, none of which a real schema offers side by
     * side: a junction table reaching two parents, a table whose compound key's first column is itself
     * a key to a third table (the identity-carrying chain), a key referencing a unique column that is
     * not the node's key (the translating one), and a key declared on the arriving table (the reverse
     * hop).
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : new String[]{"film", "actor", "category", "film_actor",
                                             "film_category", "film_category_note",
                                             "film_sequel"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "alt_code", 1, "ALT_CODE");
            seedColumn(dsl, PKG, PUBLIC, "actor", "actor_id", 0, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "category", "category_id", 0, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "actor_id", 1, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "last_update", 2, "LAST_UPDATE");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "category_id", 1, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category_note", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category_note", "category_id", 1, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_sequel", "from_film_id", 0, "FROM_FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_sequel", "sequel_code", 1, "SEQUEL_CODE");

            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_alt_code_key", "alt_code");
            seedPrimaryKey(dsl, PKG, PUBLIC, "actor", "actor_pkey", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "category", "category_pkey", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_pkey",
                "film_id", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category", "film_category_pkey",
                "film_id", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category_note", "film_category_note_pkey",
                "film_id", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_sequel", "film_sequel_pkey", "from_film_id");

            // The junction: film_category reaches film and category, and its two keys depart
            // different columns, which is the chain that carries no identity.
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_category_id_fkey",
                "category", "category_pkey", "category_id");
            // The identity-carrying chain: the note's compound key reaches the whole pairing, whose
            // own key to film departs a column that pairing arrived at.
            seedForeignKey(dsl, PKG, PUBLIC, "film_category_note", "film_category_note_fkey",
                "film_category", "film_category_pkey", "film_id", "category_id");
            // The partial lift: the same compound departure reaching a node type whose key list
            // names one column it arrives at and one it does not.
            seedForeignKey(dsl, PKG, PUBLIC, "film_category_note",
                "film_category_note_film_actor_fkey", "film_actor", "film_actor_pkey",
                "film_id", "category_id");
            // The translating key: it references a unique column that is not the node's key.
            seedForeignKey(dsl, PKG, PUBLIC, "film_sequel", "film_sequel_alt_fkey",
                "film", "film_alt_code_key", "sequel_code");
            // The reverse hop: film_actor declares the key and film is the departure.
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_actor_id_fkey",
                "actor", "actor_pkey", "actor_id");

            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef) {
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
    }

    /** An argument-site {@code @reference} whose elements each name a key, in written order. */
    private static void seedArgumentPath(DSLContext dsl, String typeName, String fieldName,
                                        String argumentName, String... keyRefs) {
        seedArgumentReference(dsl, GRAPH, typeName, fieldName, argumentName, 0);
        for (int position = 0; position < keyRefs.length; position++) {
            seedArgumentReferenceStep(dsl, GRAPH, typeName, fieldName, argumentName,
                0, position, null, keyRefs[position]);
        }
    }

    private static List<String> columns(DSLContext dsl) {
        return rows(dsl).map(NodeIdDecodeColumnTest::render);
    }

    private static Result<Record> rows(DSLContext dsl) {
        derive(dsl);
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_NODE_ID_DECODE_COLUMN.fields())
            .from(INTENT_NODE_ID_DECODE_COLUMN)
            .where(INTENT_NODE_ID_DECODE_COLUMN.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_NODE_ID_DECODE_COLUMN.SITE,
                INTENT_NODE_ID_DECODE_COLUMN.TYPE_NAME,
                INTENT_NODE_ID_DECODE_COLUMN.FIELD_NAME,
                INTENT_NODE_ID_DECODE_COLUMN.POSITION)
            .fetch();
    }

    /** The coordinate, the key position, the key column, and what it lifts to. */
    private static String render(Record row) {
        String path = row.get(INTENT_NODE_ID_DECODE_COLUMN.PATH);
        String coordinate = path != null ? path
            : row.get(INTENT_NODE_ID_DECODE_COLUMN.TYPE_NAME) + "."
              + row.get(INTENT_NODE_ID_DECODE_COLUMN.FIELD_NAME) + "("
              + row.get(INTENT_NODE_ID_DECODE_COLUMN.ARGUMENT_NAME) + ")";
        String local = row.get(INTENT_NODE_ID_DECODE_COLUMN.LOCAL_COLUMN_NAME);
        return coordinate + " " + row.get(INTENT_NODE_ID_DECODE_COLUMN.POSITION) + " "
            + row.get(INTENT_NODE_ID_DECODE_COLUMN.KEY_COLUMN_NAME) + " -> "
            + (local != null ? local : "(none)");
    }
}
