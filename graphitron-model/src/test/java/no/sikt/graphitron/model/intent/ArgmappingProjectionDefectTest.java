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
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_PROJECTION;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedCatalogRoutine;
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
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedServiceArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argmapping_projection_defect} returns: the {@code argMapping} bindings that
 * open a {@code @nodeId} and fail to become a key projection: one row per defective pair in a closed
 * verdict vocabulary of four. The rejections that close the hole where a path bound a node id and
 * the base64 wire id reached the database with nothing in the build saying a word.
 *
 * <p>The arms are bucketed by the trailing-segment count, and within a bucket an existence test
 * against the candidate relation splits what resolves from what is refused, so the arms are disjoint
 * by construction; the cases below pin that as a property rather than trusting it. Beside
 * the arms, the boundary matters as much as the arms do: an ordinary binding, a resolving
 * projection, a two-segment tail, a leaf the grammar refuses to open and a path that bound nothing
 * must each leave the relation empty, and each of those absences is a rejection some other surface
 * owns or a population no rule judges.
 *
 * <p>The key's arity is load-bearing throughout, which is why the fixture carries both a one-column
 * node type and a two-column one and every case names which it binds. A bare binding against a
 * one-column key is not a defect at all: the sole column is the only projection it could mean, so it
 * resolves, and what still refuses such a binding is the type gate on the column the inference
 * chose. Cases about the bare arm therefore name the two-column type, and reading one that names the
 * one-column type is reading a case about the inference instead.
 */
class ArgmappingProjectionDefectTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The bare form: a decode nobody opened =====

    /**
     * The motivating silence, now a row, and the arity that keeps it one. A
     * {@code @nodeId(typeName:)} input field bound with no further segment against a node type whose
     * key is two columns has nothing to infer: one binding carries one value, and nothing in the
     * spelling says which of the two it is. The node type it names is what a message needs in order
     * to say what to write instead.
     *
     * <p>A one-column key is deliberately not this case's fixture. There the sole column is the only
     * projection such a binding could mean, so it resolves rather than being reported here, which is
     * the case two sections down.
     */
    @Test
    void aNodeIdBoundWithNoKeyColumnIsTheBareForm() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "FilmActor");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("BARE_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.NODE_TYPE_REF))
                .isEqualTo("FilmActor");
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
            filmActorNodeType(dsl);
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "FilmActor");
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
     * The bare form fires whether or not the directive names a type, and naming none is the way to
     * have nothing to infer that no arity can rescue: with no node type there is no key list to
     * count, so the inference has nothing to reach for. A missing {@code typeName:} is a clause of
     * the same remedy rather than a second verdict: the author has to name the type, and reporting
     * two defects for one entry would be two errors on one line.
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

    // ===== A one-column key needs no segment, so the bare spelling resolves =====

    /**
     * The arity rule, stated where it takes a rejection away. A node type keyed on one column has
     * exactly one thing a bare binding could project, so the binding is a projection and not a
     * defect. Asserted as both halves at once, because a rule that only removed the row would be
     * indistinguishable from a rule that lost it: the defect relation is empty and the projection
     * relation names the column the author did not have to write.
     */
    @Test
    void aOneColumnKeyIsInferredRatherThanReported() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            assertThat(rows(dsl))
                .as("one key column is not an ambiguity, so there is nothing to report")
                .isEmpty();
            assertThat(dsl.select(INTENT_RESOLVED_NODE_KEY_PROJECTION.COLUMN_NAME,
                    INTENT_RESOLVED_NODE_KEY_PROJECTION.KEY_POSITION)
                .from(INTENT_RESOLVED_NODE_KEY_PROJECTION)
                .where(INTENT_RESOLVED_NODE_KEY_PROJECTION.GRAPH_NAME.eq(GRAPH))
                .fetch(r -> List.of(r.value1(), r.value2())))
                .as("and the projection resolves the sole column, position included")
                .containsExactly(List.of("inventory_id", 0));
        });
    }

    /**
     * The inferred column is still subject to the type gate, which is what makes lifting the bare
     * rejection safe rather than lenient. The author named no column, so nothing they wrote is wrong;
     * the parameter still cannot take the value, and the refusal names the column the inference
     * chose. {@code trailing_segment_name} is NULL here on the mismatch arm, which is how a consumer
     * knows to name the key column rather than quote a segment nobody spelled.
     */
    @Test
    void anInferredColumnTheParameterCannotTakeIsStillTheTypeMismatch() {
        withTypedRoutine("input.inventoryId", dsl -> {
            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("KEY_COLUMN_TYPE_MISMATCH");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .as("the author spelled no segment, so there is none to quote")
                .isNull();
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENTS))
                .as("and the count agrees with that absence rather than being a second fact")
                .isEqualTo(0);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.COLUMN_JAVA_TYPE))
                .isEqualTo("java.lang.Integer");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.PARAM_JAVA_TYPE))
                .isEqualTo("java.lang.String");
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

    // ===== The type mismatch: the column exists and the parameter cannot take it =====

    /**
     * The verdict that exists so a correct column name is not called wrong. A trailing segment naming
     * a real key column whose Java type the consuming parameter cannot take is its own arm, carrying
     * both types, and it is disjoint from the unknown column by whether a candidate row exists at all.
     */
    @Test
    void aKeyColumnTheParameterCannotTakeIsTheTypeMismatch() {
        withTypedRoutine(dsl -> {
            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("KEY_COLUMN_TYPE_MISMATCH");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .as("the column the author named, which exists")
                .isEqualTo("inventory_id");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.COLUMN_JAVA_TYPE))
                .isEqualTo("java.lang.Integer");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.PARAM_JAVA_TYPE))
                .isEqualTo("java.lang.String");
        });
    }

    /**
     * The two type columns are the mismatch arm's alone. Every other arm leaves them NULL, which is
     * the stated absent bucket rather than a missing value: nothing else in this vocabulary is about
     * a type, so a consumer switching on the verdict never has to test whether they are populated.
     */
    @Test
    void theOtherArmsCarryNoTypes() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "FilmActor");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT)).isEqualTo("BARE_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.COLUMN_JAVA_TYPE)).isNull();
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.PARAM_JAVA_TYPE)).isNull();
        });
    }

    /**
     * A parameter whose type nothing resolved is not a mismatch. The arm needs both operands, and the
     * projection stands aside in the same case, so a pair the gate cannot judge is a projection rather
     * than being reported here on a comparison against nothing.
     */
    @Test
    void anUnresolvableParameterTypeIsNotAMismatch() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            assertThat(rows(dsl))
                .as("no call surface was captured, so there is no parameter type to disagree with")
                .isEmpty();
        });
    }

    // ===== Nothing to open: the arm that has to be here because the walk judges nothing =====

    /**
     * An {@code ID} that declares no {@code @nodeId} is not a node id, so a dot on it opens nothing.
     * That is this relation's verdict and no one else's, which took two wrong turns to settle. An
     * earlier shape put it here and argued it from the wrong premise (that the schema walk could not
     * ask about directives); the correction to that premise was right and the conclusion drawn from
     * it was not, which was to make the walk ask. A walk that asks is a resolution over captured
     * facts running before capture, so it is an earlier second copy of this arm that wins by
     * rejecting first. The walk carries the segment and this arm judges it.
     */
    @Test
    void anIdDeclaringNoNodeIdIsUndeclared() {
        withInventoryNode(dsl -> {
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("UNDECLARED_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.LEAF_NAMED_TYPE))
                .as("an ID gets the annotate-it remedy, which is what this column is for")
                .isEqualTo("ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .isEqualTo("inventory_id");
        });
    }

    /**
     * The same arm for any other leaf type, which is what shows it is one rule and not two: a dot on
     * a {@code String} and a dot on an undeclared {@code ID} are the same defect. Only the remedy
     * differs, and {@code leaf_named_type} is what a consumer reads to pick it, rather than the
     * vocabulary carrying two verdicts for one condition.
     */
    @Test
    void openingANonIdLeafIsUndeclaredToo() {
        withInventoryNode(dsl -> {
            seedField(dsl, GRAPH, "RentFilmInput", "note", "String", false);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "note", "String"));
            routinePair(dsl, "pNote", "input.note.nope");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("UNDECLARED_NODE_ID");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.LEAF_NAMED_TYPE))
                .as("a String gets the nothing-to-open remedy off the same arm")
                .isEqualTo("String");
        });
    }

    // ===== The arms are disjoint, and the boundary is where they stop =====

    /**
     * The trailing count decides which arm reports, so one node id spelled three ways at three
     * positions of one application yields exactly one row per position with three different verdicts.
     * Pinning it here is what makes the disjointness a property rather than a reading of the SQL.
     *
     * <p>The count decides the bucket and not, on its own, whether there is a row: the zero bucket
     * additionally asks whether a column could be inferred, which is why this fixture's bare
     * position binds the two-column node type. Within a bucket that reports, the count is the whole
     * of what picks the arm.
     */
    @Test
    void theTrailingCountDecidesWhichArmReports() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "FilmActor");
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
     * More than one name past a node id is its own arm. A node id opens into exactly one key column,
     * so a second name is a typo or a nested form nothing resolves, and the count is carried so a
     * message can say how far past the openable position the author went. Its own verdict rather than
     * a clause on the unknown-column one, because the remedy is different: nothing about the column
     * name is wrong, the path is too long.
     */
    @Test
    void moreThanOneTrailingSegmentIsItsOwnArm() {
        withInventoryNode(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            routinePair(dsl, "pInventoryId", "input.inventoryId.inventory_id.nope");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.VERDICT))
                .isEqualTo("TRAILING_SEGMENTS_BEYOND_ONE");
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENTS)).isEqualTo(2);
            assertThat(row.get(INTENT_ARGMAPPING_PROJECTION_DEFECT.TRAILING_SEGMENT_NAME))
                .as("the first name past the node id, which is the one that was openable")
                .isEqualTo("inventory_id");
        });
    }

    /**
     * A path whose head names no slot in scope has no leaf, so it reaches no arm whatever the rest of
     * it spells. That rejection is {@code ArgBindingMap.of}'s and arrives before the store is
     * written, and it is the one the walk still owns: whether a name is an argument of the field in
     * front of it is a question about the SDL surface the walk is holding, not about captured facts.
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
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "FilmActor");
            routinePair(dsl, "pInventoryId", "input.inventoryId");

            seedField(dsl, GRAPH, "Query", "films");
            seedArgument(dsl, GRAPH, "Query", "films", "input", "RentFilmInput");
            seedOccurrencePath(dsl, GRAPH, "Query", "films", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
            seedServiceArgMappingPair(dsl, GRAPH, "Query", "films", 0, "javaParam",
                "input.inventoryId");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "input.inventoryId");

            seedField(dsl, GRAPH, "Film", "rentals");
            seedArgumentNodeId(dsl, GRAPH, "Film", "rentals", "byInventory", "FilmActor");
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
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "FilmActor");
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
            filmActorNodeType(dsl);
            seedField(dsl, GRAPH, "Film", "rentals");
            seedArgument(dsl, GRAPH, "Film", "rentals", "other", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Film", "rentals", "byInventory", "FilmActor");
            seedArgumentNodeId(dsl, GRAPH, "Film", "rentals", "other", "FilmActor");
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
            seedTable(dsl, PKG + ".other", PUBLIC, "film_actor");
            seedColumn(dsl, PKG + ".other", PUBLIC, "film_actor", "film_id", 0, "filmId");
            seedColumn(dsl, PKG + ".other", PUBLIC, "film_actor", "actor_id", 1, "actorId");
            seedNode(dsl, "other", "FilmActor");
            seedTableBinding(dsl, "other", "FilmActor", "film_actor");
            seedNodeKeyColumnRef(dsl, "other", "FilmActor", 0, "film_id");
            seedNodeKeyColumnRef(dsl, "other", "FilmActor", 1, "actor_id");
            seedDeclaredType(dsl, "other", "RentFilmInput", "INPUT_OBJECT");
            seedField(dsl, "other", "RentFilmInput", "inventoryId");
            seedField(dsl, "other", "Mutation", "rentFilm");
            seedArgument(dsl, "other", "Mutation", "rentFilm", "input", "RentFilmInput");
            seedOccurrencePath(dsl, "other", "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
            seedFieldNodeId(dsl, "other", "RentFilmInput", "inventoryId", "FilmActor");
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

    /**
     * The whole fixture plus a captured routine call surface whose parameter takes a {@code String}
     * while the projected key column binds as {@code Integer}: the mismatch arm's two operands, both
     * stated, since a case about a comparison must not rely on two seed defaults happening to differ.
     * The path names the column the author wrote.
     */
    private static void withTypedRoutine(Consumer<DSLContext> body) {
        withTypedRoutine("input.inventoryId.inventory_id", body);
    }

    /**
     * The same fixture at a path the case chooses, which is what lets one comparison be reached two
     * ways: a trailing segment naming the column, and a bare binding whose one-column key infers it.
     * The two must draw the same verdict off the same operands, the column being the same column.
     */
    private static void withTypedRoutine(String argumentPath, Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId",
                "java.lang.Integer");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "inventory_id");
            inputSurface(dsl);
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            seedRoutine(dsl, GRAPH, "Mutation", "rentFilm", "rent_film");
            seedTable(dsl, PKG, PUBLIC, "rent_film");
            seedCatalogRoutine(dsl, PKG, PUBLIC, "rent_film", PKG + ".Routines", "rentFilm");
            seedRoutineParameter(dsl, PKG, PUBLIC, "rent_film", 0, "pInventoryId",
                "java.lang.String");
            routinePair(dsl, "pInventoryId", argumentPath);
            body.accept(dsl);
        });
    }

    /** An {@code Inventory} node over the {@code inventory} table with its key column pinned. */
    private static void inventoryNodeType(DSLContext dsl, String pinnedColumn) {
        seedTable(dsl, PKG, PUBLIC, "inventory");
        seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
        seedNode(dsl, GRAPH, "Inventory");
        seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
        seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, pinnedColumn);
    }

    /**
     * A {@code FilmActor} node over the {@code film_actor} junction with both key columns pinned:
     * the node type the bare form needs, since a one-column key infers its own column and resolves.
     * Every case whose subject is the bare arm names this type, and the cases about the other arms
     * keep {@code Inventory} beside it, so the two arities sit in one fixture and a case says which
     * it is by which type it binds.
     */
    private static void filmActorNodeType(DSLContext dsl) {
        seedTable(dsl, PKG, PUBLIC, "film_actor");
        seedColumn(dsl, PKG, PUBLIC, "film_actor", "film_id", 0, "filmId");
        seedColumn(dsl, PKG, PUBLIC, "film_actor", "actor_id", 1, "actorId");
        seedNode(dsl, GRAPH, "FilmActor");
        seedTableBinding(dsl, GRAPH, "FilmActor", "film_actor");
        seedNodeKeyColumnRef(dsl, GRAPH, "FilmActor", 0, "film_id");
        seedNodeKeyColumnRef(dsl, GRAPH, "FilmActor", 1, "actor_id");
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

    /**
     * The whole fixture most cases depart from: a one-column node, a two-column one, and the input
     * surface above them. Both arities are always present because the arity is what several arms now
     * turn on, and a case that had to seed its own would be a case where the reader cannot see which
     * arity it is testing without reading the fixture.
     */
    private static void withInventoryNode(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            inventoryNodeType(dsl, "inventory_id");
            filmActorNodeType(dsl);
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
        derive(dsl);
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
        derive(dsl);
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
