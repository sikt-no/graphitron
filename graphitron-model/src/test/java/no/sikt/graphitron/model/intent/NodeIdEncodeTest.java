package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_ENCODE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedError;
import static no.sikt.graphitron.model.test.SeededStore.seedExternalField;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an encoding field produces the tuple it encodes: {@code intent_node_id_encode}, one row per
 * output field carrying the {@code @nodeId} instruction whose source resolves.
 *
 * <p>The cases are organised by source, and the two conditions that route a field to the read family
 * get one each, because they are different facts about a coordinate rather than two spellings of one:
 * a containing type no table stands for, and a field whose value a named producer returns despite its
 * containing type being bound.
 *
 * <p>The arity precondition applies to one source and not the other, so it is pinned from both sides.
 * A composite key at a read coordinate has no row, because a read yields one value; the same composite
 * key at a projection does, the whole tuple being in scope. Asserting only the refusal would leave the
 * asymmetry untested and a blanket filter would pass.
 */
class NodeIdEncodeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    // ===== PROJECTED_COLUMNS =====

    /** The ordinary encode: the field sits on a bound type, so the key columns are selectable. */
    @Test
    void aFieldOnABoundTypeProjectsItsKeyColumns() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "id", null);

            assertThat(sources(dsl))
                .containsExactly("Film.id PROJECTED_COLUMNS Film 1");
        });
    }

    /**
     * A field reaching another type's identity through an authored path still projects, the key
     * columns being selectable through the join the path resolves. The source is about whether a tuple
     * is in scope and not about whose table it is on.
     */
    @Test
    void aReferenceCarryingFieldStillProjects() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
            seedField(dsl, GRAPH, "FilmActor", "actorNodeId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "FilmActor", "actorNodeId", "Actor");
            seedFieldReference(dsl, GRAPH, "FilmActor", "actorNodeId", 0);
            seedFieldReferenceStep(dsl, GRAPH, "FilmActor", "actorNodeId", 0, 0,
                null, "film_actor_actor_id_fkey");

            assertThat(sources(dsl))
                .containsExactly("FilmActor.actorNodeId PROJECTED_COLUMNS Actor 1");
        });
    }

    /** A composite key projects: the whole tuple is in scope, so there is no width to refuse. */
    @Test
    void aCompositeKeyProjectsWithNoArityLimit() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmActor", "film_actor");
            seedField(dsl, GRAPH, "FilmActor", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "FilmActor", "id", null);

            assertThat(sources(dsl))
                .containsExactly("FilmActor.id PROJECTED_COLUMNS FilmActor 2");
        });
    }

    // ===== READ_VALUE =====

    /**
     * An {@code @error} type is an object type no table stands for, so its fields are read rather than
     * projected. The coordinate the reporter's hand-written encoder call sites exist for.
     */
    @Test
    void aFieldOnATypeNoTableStandsForIsRead() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedType(dsl, GRAPH, "FilmNotFound", "OBJECT");
            seedError(dsl, GRAPH, "FilmNotFound");
            seedField(dsl, GRAPH, "FilmNotFound", "filmId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "FilmNotFound", "filmId", "Film");

            assertThat(sources(dsl))
                .containsExactly("FilmNotFound.filmId READ_VALUE Film 1");
        });
    }

    /**
     * A named producer overrides the projection the containing type would otherwise offer, so a bound
     * type is not on its own enough to project. The second condition, and a different fact from the
     * first rather than another spelling of it.
     */
    @Test
    void aProducerOverridesTheProjectionItsBoundTypeWouldOffer() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "canonicalId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "canonicalId", "Film");
            seedExternalField(dsl, GRAPH, "Film", "canonicalId", "com.example.Ids", "canonical");

            assertThat(sources(dsl))
                .containsExactly("Film.canonicalId READ_VALUE Film 1");
        });
    }

    /**
     * The producer condition reads the reference and not the method it resolves to, so a method the
     * classpath census never saw still routes the field to the read family. Asking the resolved
     * relation would have made an unresolvable reference look like a projection, which is the one
     * reading that puts an encoded string where a column belongs. The fixture states no classes at
     * all, so nothing here resolves.
     */
    @Test
    void anUnresolvableProducerIsStillARead() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "canonicalId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "canonicalId", "Film");
            seedExternalField(dsl, GRAPH, "Film", "canonicalId",
                "com.example.NotOnTheClasspath", null);

            assertThat(sources(dsl))
                .containsExactly("Film.canonicalId READ_VALUE Film 1");
        });
    }

    /**
     * A composite key at a read coordinate has no row: a read yields one value and a key of several
     * columns cannot be encoded from it. The other side of the projection case above, and the
     * asymmetry is the claim.
     */
    @Test
    void aCompositeKeyAtAReadCoordinateHasNoRow() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "FilmActor", "film_actor");
            seedType(dsl, GRAPH, "CastNotFound", "OBJECT");
            seedError(dsl, GRAPH, "CastNotFound");
            seedField(dsl, GRAPH, "CastNotFound", "castEntryId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "CastNotFound", "castEntryId", "FilmActor");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The population's own edges =====

    /** An argument decodes, so the encode population holds none of them. */
    @Test
    void anArgumentIsNotInTheEncodePopulation() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Query", "actors", "Actor", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "actors", "filmId", "Film");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A node type no tier resolves key columns for has no row, there being nothing to encode from.
     * Absence is the key relation's statement rather than this one's.
     */
    @Test
    void aNodeTypeWithNoResolvedKeyHasNoRow() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "ledger");
            seedColumn(dsl, PKG, PUBLIC, "ledger", "ledger_id", 0, "LEDGER_ID");
            seedTableBinding(dsl, GRAPH, "Ledger", "ledger");
            seedNode(dsl, GRAPH, "Ledger");
            seedField(dsl, GRAPH, "Ledger", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Ledger", "id", null);

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition holds. */
    @Test
    void aSiblingGraphEncodesNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "id", null);

            derive(dsl);
            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : new String[]{"film", "actor", "film_actor"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "actor", "actor_id", 0, "ACTOR_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_actor", "actor_id", 1, "ACTOR_ID");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "actor", "actor_pkey", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_pkey",
                "film_id", "actor_id");
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

    private static List<String> sources(DSLContext dsl) {
        return rows(dsl).map(NodeIdEncodeTest::render);
    }

    private static Result<Record> rows(DSLContext dsl) {
        derive(dsl);
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_NODE_ID_ENCODE.fields())
            .from(INTENT_NODE_ID_ENCODE)
            .where(INTENT_NODE_ID_ENCODE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_NODE_ID_ENCODE.USE_SITE, INTENT_NODE_ID_ENCODE.NODE_TYPE_NAME)
            .fetch();
    }

    /** The coordinate, where its tuple comes from, what it encodes, and how wide the key is. */
    private static String render(Record row) {
        return row.get(INTENT_NODE_ID_ENCODE.USE_SITE) + " "
            + row.get(INTENT_NODE_ID_ENCODE.SOURCE) + " "
            + row.get(INTENT_NODE_ID_ENCODE.NODE_TYPE_NAME) + " "
            + row.get(INTENT_NODE_ID_ENCODE.ARITY);
    }
}
