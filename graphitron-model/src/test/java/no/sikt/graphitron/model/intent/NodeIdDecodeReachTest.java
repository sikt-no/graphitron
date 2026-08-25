package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_ENDPOINT;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_HOP;
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
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a decode reaches the table its node type's keys live on: {@code intent_node_id_decode_endpoint}
 * for the two tables and the rule that connects them, {@code intent_node_id_decode_hop} for the hops
 * the rule resolves.
 *
 * <p>The two relations are pinned together rather than one at a time, because the hop child's arms are
 * disjoint on the endpoint relation's navigation column and asserting either alone would leave that
 * agreement untested. Each case therefore states both: which navigation answered, and what hops came
 * out of it.
 *
 * <p>The cases are organised by navigation, and one of the three contributes no hops at all. That is
 * the claim most worth pinning here: an empty hop set means own-row identity under that navigation
 * and a chain that stopped under the others, so the silences are asserted with their navigation
 * beside them, never as bare emptiness. A fourth navigation once named an input field's own
 * {@code @reference} as unwalkable and is retired; the case that pinned it now pins the walk.
 */
class NodeIdDecodeReachTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== SAME_TABLE =====

    /**
     * Own-row identity: the slot supplies encoded ids of the very rows its predicate binds on, so
     * there is nothing to walk and the empty hop set is the answer.
     */
    @Test
    void anIdentityDecodeReachesItsOwnTableWithNoHops() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "ids", "Film");

            assertThat(endpoints(dsl))
                .containsExactly("ARGUMENT Query.films(ids) Film SAME_TABLE film -> film");
            assertThat(hops(dsl)).isEmpty();
        });
    }

    /**
     * A written {@code @reference} on a same-table slot names a self-key rather than identity, which
     * is the predicate the classifier's own short-circuit turns on, so the navigation is the authored
     * one and the hop is resolved.
     */
    @Test
    void aWrittenPathOnASameTableSlotIsStillAnAuthoredPath() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "sequelTo", "Film");
            seedArgumentPath(dsl, "Query", "films", "sequelTo", "film_sequel_fkey");

            assertThat(endpoints(dsl))
                .containsExactly("ARGUMENT Query.films(sequelTo) Film AUTHORED_PATH film -> film");
            assertThat(hops(dsl))
                .containsExactly("Query.films(sequelTo) 0 KEY film -> film film_sequel_fkey true");
        });
    }

    // ===== DISCOVERED_KEY =====

    /** Nothing written and the tables differ: the one key the departing table declares answers. */
    @Test
    void anUnwrittenPathDiscoversTheOnlyDepartingKey() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
            seedField(dsl, GRAPH, "Query", "filmActors", "FilmActor", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "filmActors", "actorId", "Actor");

            assertThat(endpoints(dsl)).containsExactly(
                "ARGUMENT Query.filmActors(actorId) Actor DISCOVERED_KEY film_actor -> actor");
            assertThat(hops(dsl)).containsExactly(
                "Query.filmActors(actorId) 0 DISCOVERED film_actor -> actor"
                + " film_actor_actor_id_fkey true");
        });
    }

    /**
     * Two keys reaching the arriving table are two different predicates, so the discovery answers
     * nothing. The navigation still says a discovery was attempted, which is what tells this apart
     * from a slot that had nothing to discover.
     */
    @Test
    void twoDepartingKeysToOneTableDiscoverNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedTableBinding(dsl, GRAPH, "FilmSequel", "film_sequel");
            seedField(dsl, GRAPH, "Query", "sequels", "FilmSequel", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "sequels", "filmId", "Film");

            assertThat(endpoints(dsl)).containsExactly(
                "ARGUMENT Query.sequels(filmId) Film DISCOVERED_KEY film_sequel -> film");
            assertThat(hops(dsl)).isEmpty();
        });
    }

    /**
     * A key the arriving table declares is not a discovery, the classifier demanding one the
     * departing table declares. The reverse direction is reachable by writing the path, which the
     * authored cases cover.
     */
    @Test
    void aKeyOnTheArrivingTableIsNotDiscovered() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmActor", "film_actor");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "castEntryId", "FilmActor");

            assertThat(endpoints(dsl)).containsExactly(
                "ARGUMENT Query.films(castEntryId) FilmActor DISCOVERED_KEY film -> film_actor");
            assertThat(hops(dsl)).isEmpty();
        });
    }

    // ===== AUTHORED_PATH =====

    /** A two-element path contributes its hops in the order the author wrote them. */
    @Test
    void anAuthoredChainContributesItsHopsInOrder() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "film_category_category_id_fkey");

            assertThat(endpoints(dsl)).containsExactly(
                "ARGUMENT Query.films(inCategory) Category AUTHORED_PATH film -> category");
            assertThat(hops(dsl)).containsExactly(
                "Query.films(inCategory) 0 KEY film -> film_category film_category_film_id_fkey"
                + " false",
                "Query.films(inCategory) 1 KEY film_category -> category"
                + " film_category_category_id_fkey true");
        });
    }

    /**
     * The hops are the ones the chain reaches, so a second element naming a key that departs
     * somewhere else leaves the first hop standing alone rather than the whole path failing.
     */
    @Test
    void aChainThatStopsShortCarriesOnlyTheHopsItReached() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "film_actor_actor_id_fkey");

            assertThat(hops(dsl)).containsExactly(
                "Query.films(inCategory) 0 KEY film -> film_category film_category_film_id_fkey"
                + " false");
        });
    }

    // ===== An input field's own authored path =====

    /**
     * An input field carrying its own {@code @reference} is walked like any other authored path,
     * departing from the table its use site binds against rather than from the input type, which
     * binds nothing. This shape used to be named UNRESOLVED_PATH, a navigation that existed only
     * because no relation walked such a path; the case is kept pointed at the same seeding so what
     * changed is visible as a different answer to one question rather than as a case that went away.
     */
    @Test
    void anInputFieldsOwnPathIsWalkedFromItsUseSitesTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmInput");
            seedInputField(dsl, "FilmInput", "inCategory");
            seedFieldNodeId(dsl, GRAPH, "FilmInput", "inCategory", "Category");
            seedFieldReference(dsl, GRAPH, "FilmInput", "inCategory", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmInput", "inCategory", 0, 0,
                null, "film_category_film_id_fkey");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmInput",
                new OccurrenceStep("FilmInput", "inCategory", "ID"));

            assertThat(endpoints(dsl)).containsExactly(
                "INPUT_FIELD Mutation.updateFilm(in)/inCategory Category AUTHORED_PATH"
                + " film -> category");
            assertThat(hops(dsl)).containsExactly(
                "Mutation.updateFilm(in)/inCategory 0 KEY film -> film_category"
                + " film_category_film_id_fkey false");
        });
    }

    // ===== The input-field grain =====

    /**
     * An input field with no path of its own navigates from the table its use site binds against,
     * which is the whole point of carrying the use site in the key: the departure is the consuming
     * coordinate's and not the input type's.
     */
    @Test
    void anInputFieldDiscoversTheKeyFromItsUseSitesTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
            seedField(dsl, GRAPH, "Mutation", "addCastEntry", "FilmActor", false);
            seedArgument(dsl, GRAPH, "Mutation", "addCastEntry", "in", "CastInput");
            seedInputField(dsl, "CastInput", "actorId");
            seedFieldNodeId(dsl, GRAPH, "CastInput", "actorId", "Actor");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "addCastEntry", "in", "CastInput",
                new OccurrenceStep("CastInput", "actorId", "ID"));

            assertThat(endpoints(dsl)).containsExactly(
                "INPUT_FIELD Mutation.addCastEntry(in)/actorId Actor DISCOVERED_KEY"
                + " film_actor -> actor");
            assertThat(hops(dsl)).containsExactly(
                "Mutation.addCastEntry(in)/actorId 0 DISCOVERED film_actor -> actor"
                + " film_actor_actor_id_fkey true");
        });
    }

    // ===== The population's own edges =====

    /** An output field encodes, so the decode population holds none of them. */
    @Test
    void anOutputFieldIsNotInTheDecodePopulation() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Actor", "filmNodeId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Actor", "filmNodeId", "Film");

            assertThat(endpointRows(dsl)).isEmpty();
        });
    }

    /**
     * Two candidate tables under the node type are two different key tuples, so the decode has no
     * arrival at all rather than an arbitrary one.
     */
    @Test
    void anAmbiguouslyBoundNodeTypeIsNoArrival() {
        withCatalog(dsl -> {
            for (String schema : new String[]{"archive", PUBLIC}) {
                seedTable(dsl, PKG, schema, "venue");
                seedColumn(dsl, PKG, schema, "venue", "venue_id", 0, "VENUE_ID");
                seedPrimaryKey(dsl, PKG, schema, "venue", "venue_" + schema + "_pkey", "venue_id");
            }
            seedTableBinding(dsl, GRAPH, "Venue", "venue");
            seedNode(dsl, GRAPH, "Venue");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "atVenue", "Venue");

            assertThat(endpointRows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds at both relations. */
    @Test
    void aSiblingGraphReachesNoneOfThis() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
            seedField(dsl, GRAPH, "Query", "filmActors", "FilmActor", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "filmActors", "actorId", "Actor");

            derive(dsl);
            assertThat(endpointRowsIn(dsl, "other")).isEmpty();
            assertThat(hopRowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * A catalog with the shapes the cases turn on: a junction table reaching two parents, a table
     * declaring two keys to one other table, and a self-referencing key. No one real catalog offers
     * those side by side, which is why they are stated here rather than borrowed.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : new String[]{"film", "actor", "category", "film_actor",
                                             "film_category", "film_sequel"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "sequel_id", 1, "SEQUEL_ID");
            seedColumn(dsl, PKG, PUBLIC, "actor", "actor_id", 0, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "category", "category_id", 0, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "actor_id", 1, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "category_id", 1, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_sequel", "from_film_id", 0, "FROM_FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_sequel", "to_film_id", 1, "TO_FILM_ID");

            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "actor", "actor_pkey", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "category", "category_pkey", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_pkey",
                "film_id", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category", "film_category_pkey",
                "film_id", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_sequel", "film_sequel_pkey",
                "from_film_id", "to_film_id");

            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_sequel_fkey", "film", "film_pkey",
                "sequel_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_actor_id_fkey",
                "actor", "actor_pkey", "actor_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_category_id_fkey",
                "category", "category_pkey", "category_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_sequel", "film_sequel_from_fkey",
                "film", "film_pkey", "from_film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_sequel", "film_sequel_to_fkey",
                "film", "film_pkey", "to_film_id");

            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /** A {@code @node} type bound to a table, which is what every decode arrives at. */
    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef) {
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
    }

    /** One {@code ID} field on an input object type. */
    private static void seedInputField(DSLContext dsl, String inputTypeName, String fieldName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, inputTypeName, fieldName, "ID", false);
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

    private static java.util.List<String> endpoints(DSLContext dsl) {
        return endpointRows(dsl).map(NodeIdDecodeReachTest::renderEndpoint);
    }

    private static java.util.List<String> hops(DSLContext dsl) {
        return hopRows(dsl).map(NodeIdDecodeReachTest::renderHop);
    }

    private static Result<Record> endpointRows(DSLContext dsl) {
        derive(dsl);
        return endpointRowsIn(dsl, GRAPH);
    }

    private static Result<Record> endpointRowsIn(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_NODE_ID_DECODE_ENDPOINT.fields())
            .from(INTENT_NODE_ID_DECODE_ENDPOINT)
            .where(INTENT_NODE_ID_DECODE_ENDPOINT.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_NODE_ID_DECODE_ENDPOINT.SITE,
                INTENT_NODE_ID_DECODE_ENDPOINT.USE_SITE,
                INTENT_NODE_ID_DECODE_ENDPOINT.NODE_TYPE_NAME)
            .fetch();
    }

    private static Result<Record> hopRows(DSLContext dsl) {
        derive(dsl);
        return hopRowsIn(dsl, GRAPH);
    }

    private static Result<Record> hopRowsIn(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_NODE_ID_DECODE_HOP.fields())
            .from(INTENT_NODE_ID_DECODE_HOP)
            .where(INTENT_NODE_ID_DECODE_HOP.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_NODE_ID_DECODE_HOP.SITE,
                INTENT_NODE_ID_DECODE_HOP.TYPE_NAME,
                INTENT_NODE_ID_DECODE_HOP.FIELD_NAME,
                INTENT_NODE_ID_DECODE_HOP.POSITION)
            .fetch();
    }

    /** Site, use site, node type, which rule connects the two tables, and the pair. */
    private static String renderEndpoint(Record row) {
        return row.get(INTENT_NODE_ID_DECODE_ENDPOINT.SITE) + " "
            + row.get(INTENT_NODE_ID_DECODE_ENDPOINT.USE_SITE) + " "
            + row.get(INTENT_NODE_ID_DECODE_ENDPOINT.NODE_TYPE_NAME) + " "
            + row.get(INTENT_NODE_ID_DECODE_ENDPOINT.NAVIGATION) + " "
            + row.get(INTENT_NODE_ID_DECODE_ENDPOINT.FROM_TABLE) + " -> "
            + row.get(INTENT_NODE_ID_DECODE_ENDPOINT.TO_TABLE);
    }

    /** Use site, position, which arm resolved the hop, the pair, the key, and its direction. */
    private static String renderHop(Record row) {
        return useSiteOf(row) + " "
            + row.get(INTENT_NODE_ID_DECODE_HOP.POSITION) + " "
            + row.get(INTENT_NODE_ID_DECODE_HOP.VIA) + " "
            + row.get(INTENT_NODE_ID_DECODE_HOP.FROM_TABLE) + " -> "
            + row.get(INTENT_NODE_ID_DECODE_HOP.TO_TABLE) + " "
            + row.get(INTENT_NODE_ID_DECODE_HOP.CONSTRAINT_NAME) + " "
            + row.get(INTENT_NODE_ID_DECODE_HOP.FK_ON_FROM);
    }

    /**
     * The hop's coordinate rendered the way the endpoint relation serializes its own, so a case can
     * read a hop and an endpoint against the same string without joining them.
     */
    private static String useSiteOf(Record row) {
        String path = row.get(INTENT_NODE_ID_DECODE_HOP.PATH);
        return path != null ? path
            : row.get(INTENT_NODE_ID_DECODE_HOP.TYPE_NAME) + "."
              + row.get(INTENT_NODE_ID_DECODE_HOP.FIELD_NAME) + "("
              + row.get(INTENT_NODE_ID_DECODE_HOP.ARGUMENT_NAME) + ")";
    }
}
