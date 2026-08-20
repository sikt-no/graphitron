package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_INSTRUCTION;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMutation;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_node_id_instruction} enumerates: every slot carrying the {@code @nodeId}
 * instruction and which node type it names.
 *
 * <p>The cases are organised by basis, because the basis is the claim. Getting the population right
 * is what the relation is for, so each case is one rule stating the instruction and the assertion is
 * which rule answered rather than merely that something did: a coordinate answered by the wrong rule
 * resolves the wrong node type at the shapes where several could answer.
 *
 * <p>Two properties get cases of their own beyond the five rules. The grain is the instruction and
 * its use site, so an input field consumed twice is two rows and the case that shows it also shows
 * the two rows disagreeing about the node type, which is the whole reason the grain is what it is.
 * And the population's stated boundary is asserted rather than left implicit: an instruction whose
 * target resolves to no node type is absent, and absence there is a shipped rejection's business.
 */
class NodeIdInstructionTest {

    // ===== EXPLICIT_TYPE_NAME =====

    /** A written {@code typeName:} resolves by name, at each of the three sites. */
    @Test
    void aWrittenTypeNameResolvesAtEverySite() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedTableBinding(dsl, GRAPH, "Actor", "actor");
            seedField(dsl, GRAPH, "Actor", "filmNodeId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Actor", "filmNodeId", "Film");
            seedField(dsl, GRAPH, "Query", "actors", "Actor", false);
            seedArgumentNodeId(dsl, GRAPH, "Query", "actors", "filmId", "Film");
            seedInputField(dsl, "ActorInput", "filmId");
            seedFieldNodeId(dsl, GRAPH, "ActorInput", "filmId", "Film");
            seedField(dsl, GRAPH, "Mutation", "createActor", "Actor", false);
            seedArgument(dsl, GRAPH, "Mutation", "createActor", "in", "ActorInput");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "createActor", "in", "ActorInput",
                new OccurrenceStep("ActorInput", "filmId", "ID"));

            assertThat(rows(dsl).map(NodeIdInstructionTest::render)).containsExactly(
                "ARGUMENT Query.actors(filmId) EXPLICIT_TYPE_NAME Film",
                "INPUT_FIELD Mutation.createActor(in)/filmId EXPLICIT_TYPE_NAME Film",
                "OUTPUT_FIELD Actor.filmNodeId EXPLICIT_TYPE_NAME Film");
        });
    }

    // ===== CONTAINING_NODE_TYPE =====

    /**
     * The manual's inference rule (a): bare {@code @nodeId} on a non-{@code @reference} object field
     * whose containing type is itself a node. Stated as a rule over the type and not over its table,
     * which is what keeps it certain where several node types sit on one table.
     */
    @Test
    void aBareDirectiveOnANodesOwnFieldTakesTheContainingType() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            // A second node type over the same table, which would make a table-mediated rule
            // ambiguous and leaves this one certain.
            seedNodeType(dsl, "FilmAgain", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "id", null);

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("OUTPUT_FIELD Film.id CONTAINING_NODE_TYPE Film");
        });
    }

    /** A bare directive on a type that is not a node resolves nothing by this rule. */
    @Test
    void aBareDirectiveOnANonNodeTypeTakesNothingFromTheContainingType() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "id", null);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== TARGET_TABLE_NODE_TYPE =====

    /**
     * The manual's inference rule (b) at an output field: a bare directive on a
     * {@code @reference}-carrying field takes its target from the table the path lands on. Rule (a)
     * is deliberately not consulted here even though the containing type is a node, which is the
     * predicate the two rules are disjoint on.
     */
    @Test
    void aBareDirectiveOnAReferenceFieldTakesThePathsTerminalTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "actorNodeId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "actorNodeId", null);
            seedFieldReference(dsl, GRAPH, "Film", "actorNodeId", 0);
            seedFieldReferenceStep(dsl, GRAPH, "Film", "actorNodeId", 0, 0, null,
                "film_actor_id_fkey");

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("OUTPUT_FIELD Film.actorNodeId TARGET_TABLE_NODE_TYPE Actor");
        });
    }

    /**
     * Rule (b) at an argument, whose target table is the field's named type's binding: the same
     * departure the argument-site path resolution takes, so a filter argument on a root field has
     * one where its parent type binds nothing.
     */
    @Test
    void aBareDirectiveOnAnArgumentTakesTheFieldsNamedTypesTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", false);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", null);

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("ARGUMENT Query.films(filmId) TARGET_TABLE_NODE_TYPE Film");
        });
    }

    /** An argument whose own {@code @reference} path moves the target moves the inference with it. */
    @Test
    void anArgumentsOwnReferencePathMovesTheInferredTarget() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Actor", "actor");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", false);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "byActor", null);
            seedArgumentReference(dsl, GRAPH, "Query", "films", "byActor", 0);
            seedArgumentReferenceStep(dsl, GRAPH, "Query", "films", "byActor", 0, 0, null,
                "film_actor_id_fkey");

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("ARGUMENT Query.films(byActor) TARGET_TABLE_NODE_TYPE Actor");
        });
    }

    /**
     * Rule (b) demands exactly one node type over the target table. Two is what the manual sends
     * the author to {@code typeName:} for, and the population declines rather than picking.
     */
    @Test
    void twoNodeTypesOverTheTargetTableResolveNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedNodeType(dsl, "FilmAgain", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", false);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "filmId", null);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** Rule (b) at an input field, whose target table is the use site's own. */
    @Test
    void aBareDirectiveOnAnInputFieldTakesTheUseSitesTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedArgument(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmInput");
            seedInputField(dsl, "FilmInput", "filmId");
            seedFieldNodeId(dsl, GRAPH, "FilmInput", "filmId", null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "createFilm", "in", "FilmInput",
                new OccurrenceStep("FilmInput", "filmId", "ID"));

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly(
                    "INPUT_FIELD Mutation.createFilm(in)/filmId TARGET_TABLE_NODE_TYPE Film");
        });
    }

    /**
     * The write target's second rung: where the consuming field's return type binds nothing, which
     * is every DELETE, {@code @mutation(table:)} answers instead. Rung one is not merely absent
     * here, it cannot apply, an {@code ID} return standing for no table.
     */
    @Test
    void aDeletesInputSurfaceBindsAgainstTheMutationsOwnTable() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "deleteFilm", "ID", false);
            seedArgument(dsl, GRAPH, "Mutation", "deleteFilm", "in", "FilmKeyInput");
            seedMutation(dsl, GRAPH, "Mutation", "deleteFilm", "DELETE", "film");
            seedInputField(dsl, "FilmKeyInput", "filmId");
            seedFieldNodeId(dsl, GRAPH, "FilmKeyInput", "filmId", null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "deleteFilm", "in", "FilmKeyInput",
                new OccurrenceStep("FilmKeyInput", "filmId", "ID"));

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly(
                    "INPUT_FIELD Mutation.deleteFilm(in)/filmId TARGET_TABLE_NODE_TYPE Film");
        });
    }

    // ===== The name-carried forms =====

    /**
     * A node type's own {@code id} field with no directive is a node ID by construction, which is
     * the form the manual documents as needing nothing written.
     */
    @Test
    void aNodesOwnIdFieldCarriesTheInstructionWithNoDirective() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("OUTPUT_FIELD Film.id OWN_ID_FIELD Film");
        });
    }

    /** An {@code id} field on a type that is not a node carries no instruction. */
    @Test
    void anIdFieldOnANonNodeTypeCarriesNothing() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * An argument named for the target's own {@code id} on a node-returning field. The node comes
     * from the returned type through the type and never from a table reverse-lookup, so a second
     * node type over the same table leaves this certain.
     */
    @Test
    void anArgumentNamedIdOnANodeReturningFieldCarriesTheInstruction() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedNodeType(dsl, "FilmAgain", "film");
            seedField(dsl, GRAPH, "Query", "film", "Film", false);
            seedArgument(dsl, GRAPH, "Query", "film", "id", "ID");

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("ARGUMENT Query.film(id) TARGET_ID_NAME Film");
        });
    }

    /**
     * The name is what carries it, so a differently-named slot carries nothing: graphitron does not
     * guess at plurals or suffixes, and this is the case that says the rule is the name and not the
     * type.
     */
    @Test
    void aDifferentlyNamedArgumentCarriesNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Query", "film", "Film", false);
            seedArgument(dsl, GRAPH, "Query", "film", "ids", "ID");
            seedArgument(dsl, GRAPH, "Query", "film", "filmId", "ID");
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** An input field named {@code id} consumed against a node-backed table. */
    @Test
    void anInputFieldNamedIdAtANodeBackedUseSiteCarriesTheInstruction() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Mutation", "updateFilm", "Film", false);
            seedArgument(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmInput");
            seedInputField(dsl, "FilmInput", "id");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "updateFilm", "in", "FilmInput",
                new OccurrenceStep("FilmInput", "id", "ID"));

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .containsExactly("INPUT_FIELD Mutation.updateFilm(in)/id TARGET_ID_NAME Film");
        });
    }

    /**
     * A written directive takes the coordinate from the name-carried rule rather than joining it, so
     * the five bases stay disjoint and one slot is never two instructions.
     */
    @Test
    void aWrittenDirectiveDisplacesTheNameCarriedReading() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Film", "id", null);

            assertThat(rows(dsl).map(NodeIdInstructionTest::render))
                .as("the bare directive answers; OWN_ID_FIELD requires no captured row")
                .containsExactly("OUTPUT_FIELD Film.id CONTAINING_NODE_TYPE Film");
        });
    }

    // ===== The use-site grain =====

    /**
     * One input field consumed at two use sites is two rows, and this is why the grain is what it
     * is: the two consumers resolve different node types, so a row keyed on the instruction alone
     * would have to pick one answer for both.
     */
    @Test
    void oneInputFieldConsumedTwiceIsTwoRowsThatCanDisagree() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedNodeType(dsl, "Actor", "actor");
            seedField(dsl, GRAPH, "Mutation", "createFilm", "Film", false);
            seedArgument(dsl, GRAPH, "Mutation", "createFilm", "in", "KeyInput");
            seedField(dsl, GRAPH, "Mutation", "createActor", "Actor", false);
            seedArgument(dsl, GRAPH, "Mutation", "createActor", "in", "KeyInput");
            seedInputField(dsl, "KeyInput", "id");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "createFilm", "in", "KeyInput",
                new OccurrenceStep("KeyInput", "id", "ID"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "createActor", "in", "KeyInput",
                new OccurrenceStep("KeyInput", "id", "ID"));

            assertThat(rows(dsl).map(NodeIdInstructionTest::render)).containsExactly(
                "INPUT_FIELD Mutation.createActor(in)/id TARGET_ID_NAME Actor",
                "INPUT_FIELD Mutation.createFilm(in)/id TARGET_ID_NAME Film");
        });
    }

    /**
     * An input field on an input type nothing reaches has no use site, so no row. Not a
     * reachability gate: a decode is "these values go here", and with no consuming coordinate there
     * is no here.
     */
    @Test
    void anUnreachedInputFieldHasNoUseSiteAndSoNoRow() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedInputField(dsl, "OrphanInput", "filmId");
            seedFieldNodeId(dsl, GRAPH, "OrphanInput", "filmId", "Film");
            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The stated boundary =====

    /**
     * A {@code typeName:} naming a type that is not a node resolves nothing, so the instruction is
     * not in the population at all. That is the boundary the relation states: such a coordinate
     * already meets a shipped rejection naming the type, and admitting it here would put an
     * instruction in the population that neither resolves nor draws a verdict.
     */
    @Test
    void aTypeNameNamingNoNodeTypeIsNotInThePopulation() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Actor", "filmNodeId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Actor", "filmNodeId", "Film");
            assertThat(rows(dsl)).isEmpty();
        });
    }

    /** The graph partition. */
    @Test
    void aSiblingGraphReadsNoInstruction() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Film", "film");
            seedField(dsl, GRAPH, "Film", "id", "ID", false);
            assertThat(rows(dsl)).hasSize(1);
            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String PUBLIC = "public";

    /**
     * Two tables and one foreign key between them, which is the whole catalog the bases need: what
     * these cases turn on is which rule answered, and every rule reaches its answer through a
     * binding or a hop rather than through a column.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_actor_id_fkey", "FOREIGN KEY", null);
            seedReferentialConstraint(dsl, PKG, PUBLIC, "film", "film_actor_id_fkey",
                PKG, PUBLIC, "actor", "actor_pkey");
            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /** A {@code @node} type bound to a table, which is what every basis resolves to. */
    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef) {
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
    }

    /** One {@code ID} field on an input object type, seeded as one if the case has not. */
    private static void seedInputField(DSLContext dsl, String inputTypeName, String fieldName) {
        seedType(dsl, GRAPH, inputTypeName, "INPUT_OBJECT");
        seedField(dsl, GRAPH, inputTypeName, fieldName, "ID", false);
    }

    private static Result<Record> rows(DSLContext dsl) {
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        return dsl.select(INTENT_NODE_ID_INSTRUCTION.fields())
            .from(INTENT_NODE_ID_INSTRUCTION)
            .where(INTENT_NODE_ID_INSTRUCTION.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_NODE_ID_INSTRUCTION.SITE,
                INTENT_NODE_ID_INSTRUCTION.USE_SITE,
                INTENT_NODE_ID_INSTRUCTION.BASIS,
                INTENT_NODE_ID_INSTRUCTION.NODE_TYPE_NAME)
            .fetch();
    }

    /** Site, use site, which rule answered, and what it resolved: the claim of every case here. */
    private static String render(Record row) {
        return row.get(INTENT_NODE_ID_INSTRUCTION.SITE) + " "
            + row.get(INTENT_NODE_ID_INSTRUCTION.USE_SITE) + " "
            + row.get(INTENT_NODE_ID_INSTRUCTION.BASIS) + " "
            + row.get(INTENT_NODE_ID_INSTRUCTION.NODE_TYPE_NAME);
    }
}
