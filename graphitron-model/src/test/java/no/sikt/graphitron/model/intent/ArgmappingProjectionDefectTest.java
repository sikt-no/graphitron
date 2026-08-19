package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_PROJECTION_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStepArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutine;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedServiceArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argmapping_projection_defect} returns: the {@code argMapping} bindings that
 * open, or should have opened, a {@code @nodeId} and fail to become a key projection: one row per
 * defective pair in a closed verdict vocabulary of four. The rejections that close the hole where a
 * path bound a node id and the base64 wire id reached the database with nothing in the build saying
 * a word.
 *
 * <p>Which arm fires among the three declared-decode arms is decided by the trailing-segment count
 * and nothing else, and {@code node_id_declared} separates them from the fourth, so the arms are
 * disjoint by construction; the cases below pin that as a property rather than trusting it. Beside
 * the arms, the boundary matters as much as the arms do: an ordinary binding, a resolving
 * projection, a two-segment tail, a non-{@code ID} leaf opened and a path that bound nothing must
 * each leave the relation empty, and each of those absences is a rejection some other surface owns
 * or a population no rule judges.
 */
class ArgmappingProjectionDefectTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The bare form: a decode nobody opened =====

    /**
     * The motivating silence, now a row. A {@code @nodeId(typeName:)} input field bound with no
     * further segment is the spelling that ships base64 to a database column today, and the node
     * type it names is what a message needs in order to say what to write instead.
     */
    @Test
    void aNodeIdBoundWithNoKeyColumnIsTheBareForm() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("BARE_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .isEqualTo("Inventory");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .as("there is no trailing segment on this arm")
                .isNull();
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.PARAM_NAME))
                .isEqualTo("pInventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.ARGUMENT_PATH))
                .isEqualTo("input.inventoryId");
        });
    }

    /**
     * A bare {@code @nodeId} head bound directly, no dotted path involved. The same defect one level
     * up, and the arm that tells an emitter which slot the wire value came out of.
     */
    @Test
    void aNodeIdArgumentBoundDirectlyIsTheBareFormToo() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("BARE_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.BOUND_KIND))
                .isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.BOUND_ARGUMENT_NAME))
                .isEqualTo("inventoryId");
        });
    }

    /**
     * The bare form fires whether or not the directive names a type. Zero trailing segments is the
     * whole condition, and a missing {@code typeName:} there is a second clause of the same remedy
     * rather than a second verdict: the author has to name the type and open it, and reporting two
     * defects for one entry would be two errors on one line.
     */
    @Test
    void theBareFormFiresWithNoTypeNameToo() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", null);
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("BARE_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .as("nothing names what to decode against, so there is no key list to reach")
                .isNull();
        });
    }

    /**
     * A node type that resolved no key columns on any tier is still a defect: the row states the
     * type the author named, and the key-column relation answering nothing for it is what turns the
     * remedy from "open it with a column" into "give that type a key". Naming no type and naming a
     * keyless one are two facts a consumer tells apart by asking, which is why neither is a verdict.
     */
    @Test
    void aNodeTypeWithNoResolvedKeyColumnsIsStillABareForm() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .as("a type is named")
                .isEqualTo("Inventory");
            assertThat(keyColumnsOf(dsl, "Inventory"))
                .as("and no tier answered for it, so there is nothing to offer as a candidate")
                .isEmpty();
        });
    }

    /**
     * The candidates a message offers are one join away and arrive in key order, which is the join
     * the view carries no render of. A composite key is where the order matters: two columns listed
     * the wrong way round would tell an author to project the key transposed.
     */
    @Test
    void theCandidatesAreOneJoinAwayInKeyOrder() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "bar");
            seedColumn(dsl, PKG, PUBLIC, "bar", "bar_id", 0, "barId");
            seedColumn(dsl, PKG, PUBLIC, "bar", "foo_id", 1, "fooId");
            seedNode(dsl, GRAPH, "Bar");
            seedTableBinding(dsl, GRAPH, "Bar", "bar");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 0, "bar_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 1, "foo_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Bar");
            routinePair(dsl, "pBarId", "input.inventoryId");

            assertThat(only(dsl).get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .isEqualTo("Bar");
            assertThat(keyColumnsOf(dsl, "Bar")).containsExactly("bar_id", "foo_id");
        });
    }

    // ===== The projection was asked for and could not resolve =====

    /**
     * A path that opens a {@code @nodeId} carrying no {@code typeName:} names nothing to decode
     * against. There is no containing table at an {@code argMapping} position to infer one from,
     * which is why this is a rejection here rather than the inference it is on a table-backed field.
     */
    @Test
    void openingANodeIdWithNoTypeNameIsAMissingTypeName() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", null);
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("MISSING_TYPE_NAME");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF)).isNull();
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .as("the segment that would have been projected")
                .isEqualTo("inventory_id");
        });
    }

    /**
     * A trailing segment naming no key column of the named type is the unknown column. The arm
     * carries both the name the author wrote and the list they could have written, which is what
     * makes the message actionable without a second query.
     */
    @Test
    void aTrailingSegmentNamingNoKeyColumnIsUnknown() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.no_such_column");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("UNKNOWN_KEY_COLUMN");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .isEqualTo("no_such_column");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .isEqualTo("Inventory");
        });
    }

    /**
     * The unknown-column arm matches on the same case fold the projection joins on, so a segment
     * spelled the other way is not reported as unknown. Two spellings of one column must not be a
     * projection on one surface and a rejection on the other.
     */
    @Test
    void aSegmentSpelledTheOtherWayIsNotUnknown() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.INVENTORY_ID");

            assertThat(rows(dsl))
                .as("the fold that resolves the projection is the fold that clears the defect")
                .isEmpty();
        });
    }

    /**
     * A named type that resolved no key columns reports the unknown column anyway. The segment cannot
     * match anything, and saying so beats saying nothing; the remedy is on the node type rather than
     * on the segment, and the empty key list is what a consumer reads to know that.
     */
    @Test
    void anUnknownColumnAgainstAKeylessTypeStillReports() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("UNKNOWN_KEY_COLUMN");
            assertThat(keyColumnsOf(dsl, "Inventory")).isEmpty();
        });
    }

    // ===== The decode nobody declared: the arm the grammar widening made necessary =====

    /**
     * An {@code ID} opened with a key column that declares no {@code @nodeId} at all. The walk used
     * to reject this as a scalar traversal and now admits it, asking nothing about the directive
     * because a path's head is reached through a slot map carrying types rather than directives; so
     * this arm is the only thing standing between the widened grammar and an uninterpreted segment
     * read straight off the wire map.
     */
    @Test
    void openingAnIdThatDeclaresNoNodeIdIsUndeclared() {
        withInventoryNode(dsl -> {
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("UNDECLARED_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .as("there is no directive to have named a type")
                .isNull();
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .as("what the author tried to project, which the message quotes back")
                .isEqualTo("inventory_id");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.BOUND_KIND))
                .isEqualTo("INPUT_FIELD");
        });
    }

    /**
     * The same defect on an argument head rather than an input field below one, which is the other
     * relation the leaf's declared type is read from. Two SDL relations hold the two kinds of leaf,
     * and {@code bound_kind} is the fork; a case per kind is what keeps one of the two joins from
     * being written wrong and never noticed.
     */
    @Test
    void openingAnIdArgumentThatDeclaresNoNodeIdIsUndeclaredToo() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "ID");
            routinePair(dsl, "pInventoryId", "inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("UNDECLARED_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.BOUND_KIND))
                .isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.BOUND_ARGUMENT_NAME))
                .isEqualTo("inventoryId");
        });
    }

    /**
     * The arm stops at {@code ID}, and that boundary is the whole of its disjointness rule. On any
     * other leaf type the walk still rejects the trailing segment itself, so an arm reaching further
     * would double-report a rejection the error stream already carries. The arm covers precisely the
     * shapes the walk stopped judging and not one more.
     */
    @Test
    void openingANonIdLeafIsLeftToTheWalk() {
        withInventoryNode(dsl -> {
            seedField(dsl, GRAPH, "RentFilmInput", "note", "String", false);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "note", "String"));
            routinePair(dsl, "pNote", "input.note.nope");

            assertThat(rows(dsl))
                .as("a dot on a String is the walk's rejection, before and after the widening")
                .isEmpty();
        });
    }

    // ===== The arms are disjoint, and the boundary is where they stop =====

    /**
     * The trailing count alone decides the arm, so one node id spelled three ways at three positions
     * of one application yields exactly one row per position with three different verdicts. Pinning
     * it here is what makes the disjointness a property rather than a reading of the SQL.
     */
    @Test
    void theTrailingCountAloneDecidesTheArm() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            seedField(dsl, GRAPH, "RentFilmInput", "bareId");
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "bareId", null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "bareId", "ID"));
            routinePair(dsl, 0, "pBare", "input.inventoryId");
            routinePair(dsl, 1, "pUnknown", "input.inventoryId.nope");
            routinePair(dsl, 2, "pUntyped", "input.bareId.inventory_id");

            var all = rows(dsl);
            assertThat(all).hasSize(3);
            assertThat(all.stream().map(r -> List.of(
                    r.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.POSITION),
                    r.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))))
                .containsExactlyInAnyOrder(
                    List.of(0, "BARE_NODE_ID"),
                    List.of(1, "UNKNOWN_KEY_COLUMN"),
                    List.of(2, "MISSING_TYPE_NAME"));
        });
    }

    /**
     * A resolving projection is not a defect. The relation and {@code
     * intent_resolved_node_key_projection} partition the declared-decode population between them,
     * and a coordinate in both would mean a build that rejects what it can also emit.
     */
    @Test
    void aResolvingProjectionHasNoRow() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * An ordinary binding, no {@code @nodeId} anywhere, is nothing to judge. The relation is about
     * declared decodes, and a path that binds a plain scalar is the population the whole family
     * leaves alone.
     */
    @Test
    void anOrdinaryBindingHasNoRow() {
        withInventoryNode(dsl -> {
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * Two trailing segments is deliberately not an arm. The walk rejects walking through a scalar
     * leaf and goes on rejecting it after the grammar admits one trailing segment, so a row here
     * would double-report a rejection the error stream already carries.
     */
    @Test
    void twoTrailingSegmentsAreLeftToTheWalk() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id.nope");

            assertThat(rows(dsl))
                .as("the walk already rejects this and the store must not say it twice")
                .isEmpty();
        });
    }

    /**
     * A path whose head names no slot in scope has no leaf, so it reaches no arm whatever the rest of
     * it spells. That rejection is {@code ArgBindingMap.of}'s and arrives before the store is
     * written, which is why the absence here is the lack of a leaf and not a defect overlooked.
     */
    @Test
    void aHeadNamingNoSlotHasNoRow() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "notAnArgument.inventoryId");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A path-step {@code @condition} binds nothing at any position, the walk resolving there against
     * an empty slot map, so a {@code @nodeId} argument it names reaches no arm. Those sites can only
     * ever defer, and deferral is not this relation's vocabulary.
     */
    @Test
    void aPathStepConditionHasNoRow() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            seedField(dsl, GRAPH, "Film", "actors");
            seedArgumentNodeId(dsl, GRAPH, "Film", "actors", "byInventory", "Inventory");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0,
                "p", "byInventory");
            seedArgumentPathSegments(dsl, GRAPH, "Film", "actors", "byInventory");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== Every site reports, and each reports as itself =====

    /**
     * The three sites that carry an emitter in this item's scope all report, off one union written
     * once. The arms are hand-written selects over relations of differing key arity, and a site whose
     * defect went unreported would be a build that fails on one directive and ships on another.
     */
    @Test
    void routineServiceAndConditionAllReport() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            seedField(dsl, GRAPH, "Query", "films");
            seedArgument(dsl, GRAPH, "Query", "films", "input", "RentFilmInput");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
            seedServiceArgMappingPair(dsl, GRAPH, "Query", "films", 0, "javaParam",
                "input.inventoryId");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "input.inventoryId");

            seedField(dsl, GRAPH, "Film", "rentals");
            seedArgumentNodeId(dsl, GRAPH, "Film", "rentals", "byInventory", "Inventory");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Film", "rentals", "byInventory", 0,
                "p", "byInventory");
            seedArgumentPathSegments(dsl, GRAPH, "Film", "rentals", "byInventory");

            assertThat(rows(dsl).stream().map(r -> r.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.SITE)))
                .containsExactlyInAnyOrder("ROUTINE", "SERVICE", "ARGUMENT_CONDITION");
        });
    }

    /**
     * The location is the owning directive application's, not the field's. A repeatable directive's
     * second application sits on its own line, so a message about its {@code argMapping} points at
     * what the author wrote rather than at the field heading above it.
     */
    @Test
    void theLocationIsTheOwningApplicationsOwn() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            seedRoutine(dsl, GRAPH, "Mutation", "rentFilm", 0, "Routines.first", 11);
            seedRoutine(dsl, GRAPH, "Mutation", "rentFilm", 1, "Routines.second", 12);
            routineApplicationPair(dsl, 0, "pFirst", "input.inventoryId");
            routineApplicationPair(dsl, 1, "pSecond", "input.inventoryId");

            assertThat(rows(dsl).stream().map(r -> List.of(
                    r.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.USE_SITE),
                    r.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.SOURCE_LINE))))
                .containsExactlyInAnyOrder(
                    List.of("Mutation.rentFilm#0", 11),
                    List.of("Mutation.rentFilm#1", 12));
        });
    }

    /**
     * The scope of the head follows the site: at an argument-site {@code @condition} only the
     * directive's own argument is in scope, so a sibling argument's {@code @nodeId} is not this
     * pair's to report even where it would have bound at any other site.
     */
    @Test
    void anArgumentSiteConditionReportsOnlyItsOwnArgument() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            seedField(dsl, GRAPH, "Film", "rentals");
            seedArgument(dsl, GRAPH, "Film", "rentals", "other", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Film", "rentals", "byInventory", "Inventory");
            seedArgumentNodeId(dsl, GRAPH, "Film", "rentals", "other", "Inventory");
            seedArgumentCondition(dsl, GRAPH, "Film", "rentals", "other",
                "no.example.Cond", "apply", false);
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Film", "rentals", "byInventory", 0,
                "p", "byInventory");
            seedArgumentPathSegments(dsl, GRAPH, "Film", "rentals", "byInventory");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Film", "rentals", "other", 0,
                "p", "byInventory");

            assertThat(rowFor(dsl, "Film.rentals(byInventory)")).isPresent();
            assertThat(rowFor(dsl, "Film.rentals(other)"))
                .as("the sibling argument is not in scope at this condition")
                .isEmpty();
        });
    }

    /** A sibling graph's defects are not this graph's rows, the partition being the leading key. */
    @Test
    void aSiblingGraphsDefectsAreNotThisGraphsRows() {
        withInventoryNode(dsl -> {
            seedGraph(dsl, "other");
            seedSource(dsl, PKG + ".other", "JOOQ_SCHEMA");
            seedGraphSource(dsl, "other", PKG + ".other");
            seedTable(dsl, PKG + ".other", PUBLIC, "inventory");
            seedColumn(dsl, PKG + ".other", PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedNode(dsl, "other", "Inventory");
            seedTableBinding(dsl, "other", "Inventory", "inventory");
            seedNodeKeyColumnRef(dsl, "other", "Inventory", 0, "inventory_id");
            seedDeclaredType(dsl, "other", "RentFilmInput", "INPUT_OBJECT");
            seedField(dsl, "other", "RentFilmInput", "inventoryId");
            seedField(dsl, "other", "Mutation", "rentFilm");
            seedArgument(dsl, "other", "Mutation", "rentFilm", "input", "RentFilmInput");
            seedOccurrencePath(dsl, "other", "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
            seedFieldNodeId(dsl, "other", "RentFilmInput", "inventoryId", "Inventory");
            seedRoutineArgMappingPair(dsl, "other", "Mutation", "rentFilm", 0, 0, "pOther",
                "input.inventoryId");
            seedArgumentPathSegments(dsl, "other", "Mutation", "rentFilm", "input.inventoryId");

            assertThat(rows(dsl))
                .as("the sibling graph's own defect stays in its own partition")
                .isEmpty();
            assertThat(dsl.fetchCount(INTENT_ARGMAPPING_PROJECTION_DEFECT,
                INTENT_ARGMAPPING_PROJECTION_DEFECT.GRAPH_NAME.eq("other")))
                .isEqualTo(1);
        });
    }

    // ===== Fixtures =====

    /** The source the graph resolves catalog names against. */
    private static void catalog(DSLContext dsl) {
        seedSource(dsl, PKG, "JOOQ_SCHEMA");
        seedGraphSource(dsl, GRAPH, PKG);
    }

    /** An {@code Inventory} node over the {@code inventory} table with its key column pinned. */
    private static void inventoryNodeType(DSLContext dsl, String pinnedColumn) {
        seedTable(dsl, PKG, PUBLIC, "inventory");
        seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
        seedNode(dsl, GRAPH, "Inventory");
        seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
        seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, pinnedColumn);
    }

    /** {@code Mutation.rentFilm(input: RentFilmInput)} and the occurrence rows under it. */
    private static void inputSurface(DSLContext dsl) {
        seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
        // ID-typed, as the occurrence step below already says it is: the undeclared-decode arm reads
        // the leaf's own declared type, so a fixture whose two halves disagreed about it would let
        // that arm pass or fail for the wrong reason.
        seedField(dsl, GRAPH, "RentFilmInput", "inventoryId", "ID", false);
        seedField(dsl, GRAPH, "Mutation", "rentFilm");
        seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
        seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
            new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
    }

    /** The whole fixture most cases depart from: the node, its key, and the input surface above it. */
    private static void withInventoryNode(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            inputSurface(dsl);
            body.accept(dsl);
        });
    }

    /** A {@code @routine} pair of the one application, at position zero. */
    private static void routinePair(DSLContext dsl, String paramName, String argumentPath) {
        routinePair(dsl, 0, paramName, argumentPath);
    }

    /** A {@code @routine} pair of the one application, at a position the case names. */
    private static void routinePair(DSLContext dsl, int position, String paramName,
                                    String argumentPath) {
        seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", 0, position, paramName,
            argumentPath);
        seedArgumentPathSegments(dsl, GRAPH, "Mutation", "rentFilm", argumentPath);
    }

    /** A {@code @routine} pair of the application a case names, at position zero. */
    private static void routineApplicationPair(DSLContext dsl, int ordinal, String paramName,
                                               String argumentPath) {
        seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", ordinal, 0, paramName,
            argumentPath);
        seedArgumentPathSegments(dsl, GRAPH, "Mutation", "rentFilm", argumentPath);
    }

    // ===== Reads =====

    /** Every row of the graph under assertion. */
    private static List<Record> rows(DSLContext dsl) {
        return dsl.select(INTENT_ARGMAPPING_PROJECTION_DEFECT.fields())
            .from(INTENT_ARGMAPPING_PROJECTION_DEFECT)
            .where(INTENT_ARGMAPPING_PROJECTION_DEFECT.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .stream()
            .map(Record.class::cast)
            .toList();
    }

    /** The one row at a use-site coordinate, the arms being disjoint at the pair's grain. */
    private static Optional<Record> rowFor(DSLContext dsl, String useSite) {
        var matching = rows(dsl).stream()
            .filter(r -> useSite.equals(r.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.USE_SITE)))
            .toList();
        assertThat(matching).as("one defect per use site and position").hasSizeLessThanOrEqualTo(1);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getFirst());
    }

    /**
     * A node type's resolved key columns in key order: the join the view documents in place of a
     * render, and what a consumer composing a candidate list actually reads.
     */
    private static List<String> keyColumnsOf(DSLContext dsl, String nodeTypeName) {
        return dsl.select(INTENT_RESOLVED_NODE_KEY_COLUMN.COLUMN_NAME)
            .from(INTENT_RESOLVED_NODE_KEY_COLUMN)
            .where(INTENT_RESOLVED_NODE_KEY_COLUMN.GRAPH_NAME.eq(GRAPH),
                INTENT_RESOLVED_NODE_KEY_COLUMN.TYPE_NAME.eq(nodeTypeName))
            .orderBy(INTENT_RESOLVED_NODE_KEY_COLUMN.POSITION)
            .fetch(r -> r.value1());
    }

    /** The one row a single-pair fixture produces. */
    private static Record only(DSLContext dsl) {
        var all = rows(dsl);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }
}
