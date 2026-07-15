package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slot-orientation invariant tests for {@link On.ColumnPairs}.
 *
 * <p>Asserts the structural contract the slot lift establishes: each {@link JoinSlot} carries
 * a {@code sourceSide()} column on the hop's source table and a {@code targetSide()} column on
 * the hop's target table, paired by FK constraint at index {@code i}. Synthesis-time orientation
 * (in {@code BuildContext.synthesizeFkJoin}) bakes the FK-direction decision into each slot pair
 * so emitter code reads direction-blind. (R431: the {@code LifterSlot} permit folded both sides
 * onto a single column by construction; it moved with {@code LiftedHop} onto {@code ParentCorrelation.OnLiftedSlots}.)
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
        return new TableRef(sqlName, sqlName.toUpperCase(), name, name, name, List.of(), List.of());
    }

    @Test
    void fkSlot_carriesDistinctSourceAndTargetSides() {
        var slot = new JoinSlot.FkSlot(ORG_CODE, PROJECT_ID);
        assertThat(slot.sourceSide()).isEqualTo(ORG_CODE);
        assertThat(slot.targetSide()).isEqualTo(PROJECT_ID);
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
        var hop = new JoinStep.Hop(new TableExpr.Catalog(childTable),
            new On.ColumnPairs(
                new On.Keying.ForeignKey(TestFixtures.foreignKeyRef("project_note_project_fkey")),
                slots),
            parentTable, null, "notes_0");
        var fkJoin = (On.ColumnPairs) hop.on();

        // Direction-blind reads through ColumnPairs.slots() / sourceSideColumns() / targetSideColumns().
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
}
