package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slot-orientation invariant tests for {@link JoinStep.FkJoin} and {@link JoinStep.LiftedHop}.
 *
 * <p>Asserts the structural contract the slot lift establishes: each {@link JoinSlot} carries
 * a {@code sourceSide()} column on the hop's source table and a {@code targetSide()} column on
 * the hop's target table, paired by FK constraint at index {@code i}. Synthesis-time orientation
 * (in {@code BuildContext.synthesizeFkJoin}) bakes the FK-direction decision into each slot pair
 * so emitter code reads direction-blind. The {@link JoinSlot.LifterSlot} permit folds both sides
 * onto a single column by construction (DataLoader key tuple IS target-column tuple).
 *
 * <p>The regression class this guards: a composite-PK parent with FK columns declared in a
 * different order than the parent's PK would, under positional pairing of two parallel column
 * lists, emit {@code Field<Integer>.eq(Field<String>)} type mismatches. With slots, the source
 * and target columns are paired structurally inside each slot, so positional misuse is a compile
 * error at the emitter and the per-slot type pairing is correct regardless of FK column order.
 */
@UnitTier
class JoinSlotOrientationTest {

    private static final ColumnRef PROJECT_ID =
        new ColumnRef("project_id", "PROJECT_ID", "java.lang.Integer");
    private static final ColumnRef ORG_CODE =
        new ColumnRef("org_code", "ORG_CODE", "java.lang.String");

    private static TableRef tableRef(String sqlName) {
        var name = ClassName.get("test.fixture", sqlName);
        return new TableRef(sqlName, sqlName.toUpperCase(), name, name, name, List.of());
    }

    @Test
    void fkSlot_carriesDistinctSourceAndTargetSides() {
        var slot = new JoinSlot.FkSlot(ORG_CODE, PROJECT_ID);
        assertThat(slot.sourceSide()).isEqualTo(ORG_CODE);
        assertThat(slot.targetSide()).isEqualTo(PROJECT_ID);
    }

    @Test
    void lifterSlot_collapsesBothSidesOntoSameColumn() {
        var slot = new JoinSlot.LifterSlot(PROJECT_ID);
        assertThat(slot.sourceSide()).isEqualTo(PROJECT_ID);
        assertThat(slot.targetSide()).isEqualTo(PROJECT_ID);
        assertThat(slot.column()).isEqualTo(PROJECT_ID);
    }

    @Test
    void fkJoin_slotPairing_survivesReorderedCompositeFk() {
        // Composite-PK parent declares (org_code, project_id); FK declares its own pair in the
        // OPPOSITE order (project_id, org_code). Under positional pairing of two parallel lists
        // this would cross-wire org_code (String) ↔ project_id (Integer) at the join predicate;
        // under slots, each FkSlot carries its own source/target pair so the type-correct cross
        // is structural.
        var parentTable = tableRef("project");
        var childTable = tableRef("project_note");
        var slots = List.of(
            new JoinSlot.FkSlot(PROJECT_ID, PROJECT_ID),
            new JoinSlot.FkSlot(ORG_CODE, ORG_CODE));
        var fkJoin = new JoinStep.FkJoin(
            "project_note_project_fkey", null, parentTable, childTable,
            slots, null, "notes_0");

        // Direction-blind reads through WithTarget.slots() / sourceSideColumns() / targetSideColumns().
        assertThat(fkJoin.slotCount()).isEqualTo(2);
        assertThat(fkJoin.sourceSideColumns())
            .as("source-side columns sit on parent table in the FK's own declared order")
            .containsExactly(PROJECT_ID, ORG_CODE);
        assertThat(fkJoin.targetSideColumns())
            .as("target-side columns sit on child table in the same FK declaration order")
            .containsExactly(PROJECT_ID, ORG_CODE);

        // Per-slot type pairing: slot[0] is (project_id, project_id), slot[1] is (org_code, org_code).
        // The parent and child columns at the same slot share their database type by FK constraint,
        // independent of the parent's @node(keyColumns: [...]) ordering.
        int i = 0;
        for (var slot : fkJoin.slots()) {
            assertThat(slot.sourceSide().columnClass())
                .as("slot %d source/target columns share their declared FK type", i)
                .isEqualTo(slot.targetSide().columnClass());
            i++;
        }
    }

    @Test
    void liftedHop_slotPairing_collapsesToTargetColumns() {
        var targetTable = tableRef("film");
        var filmId = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
        var slots = List.of(new JoinSlot.LifterSlot(filmId));
        var hop = new JoinStep.LiftedHop(targetTable, slots, "f_0");

        assertThat(hop.slotCount()).isEqualTo(1);
        assertThat(hop.sourceSideColumns())
            .as("LifterSlot.sourceSide() collapses onto the single column")
            .containsExactly(filmId);
        assertThat(hop.targetSideColumns())
            .as("LifterSlot.targetSide() returns the same column — DataLoader key IS target tuple")
            .containsExactly(filmId);
    }

    @Test
    void withTarget_slots_iterableNotList_compileTimeBan() {
        // The capability-level slots() accessor returns Iterable<? extends JoinSlot>, not List —
        // positional reads (.get(i), .getFirst(), .subList(...)) are compile errors at any
        // emitter that goes through WithTarget. Cardinality stays available through slotCount().
        // This test pins that contract by binding the result to Iterable explicitly; if WithTarget
        // ever widens slots()'s return type the binding here breaks.
        var hop = new JoinStep.LiftedHop(tableRef("film"),
            List.of(new JoinSlot.LifterSlot(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"))),
            "f_0");
        JoinStep.WithTarget asCapability = hop;
        Iterable<? extends JoinSlot> slots = asCapability.slots();
        assertThat(slots).hasSize(1);
        assertThat(asCapability.slotCount()).isEqualTo(1);
    }
}
