package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.model.ColumnOverlap.ColumnWriter;
import no.sikt.graphitron.rewrite.model.ColumnOverlap.OverlapColumn;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural invariant tests for the {@link ColumnOverlap#groupByColumn} primitive, the one
 * grouping the six DML mutation write-path sites read.
 *
 * <p>This is the anti-drift assertion the lift rests on: the <em>same</em> {@link ColumnWriter} list
 * yields both the predicate the validator rejects on ({@code shared() && allPlain()}) and the
 * predicate the emitters trigger value-agreement on ({@code shared()}). Before this unification,
 * those were two hand-rolled walks that could diverge; here they read one fold.
 */
@UnitTier
class ColumnOverlapTest {

    private static ColumnRef col(String sqlName) {
        return new ColumnRef(sqlName, sqlName.toUpperCase(), "java.lang.Integer");
    }

    /** A test writer over a fixed slot-bearing column list; {@code decode} flags whether it is a
     *  {@code @nodeId} decode. */
    private record TestWriter(List<ColumnOverlap.SlotColumn> targetColumns, boolean decode, String label)
            implements ColumnWriter {}

    private static TestWriter plain(String sqlName) {
        return new TestWriter(List.of(new ColumnOverlap.SlotColumn(0, col(sqlName))), false, sqlName);
    }

    /** A decode whose columns are one whole record: slot i for the column at position i. */
    private static TestWriter decode(String label, String... sqlNames) {
        return new TestWriter(ColumnOverlap.SlotColumn.contiguous(
            List.of(sqlNames).stream().map(ColumnOverlapTest::col).toList()), true, label);
    }

    /** A decode handed only <em>part</em> of its record: the partitioned shape, where a column's
     *  position in the writer's list is no longer its decode slot. */
    private static TestWriter partialDecode(String label, int slot, String sqlName) {
        return new TestWriter(List.of(new ColumnOverlap.SlotColumn(slot, col(sqlName))), true, label);
    }

    @Test
    void groupsByColumn_inWriterEncounterOrder_keepingEveryColumn() {
        // film_id appears first (writer a), then title (writer b), then film_id again (writer c).
        var plan = ColumnOverlap.groupByColumn(List.of(plain("film_id"), plain("title"), plain("film_id")));
        assertThat(plan)
            .as("every column kept, size-one included, in first-encounter order")
            .extracting(oc -> oc.column().sqlName())
            .containsExactly("film_id", "title");
    }

    @Test
    void shared_atTwoOrMoreContributors() {
        var plan = ColumnOverlap.groupByColumn(List.of(plain("film_id"), plain("title"), plain("film_id")));
        assertThat(plan).filteredOn(oc -> oc.column().sqlName().equals("film_id"))
            .singleElement().matches(OverlapColumn::shared, "film_id has two writers -> shared");
        assertThat(plan).filteredOn(oc -> oc.column().sqlName().equals("title"))
            .singleElement().matches(oc -> !oc.shared(), "title has one writer -> not shared");
    }

    @Test
    void allPlain_iffNoContributorDecodes() {
        // Two plain writers on one column: shared && allPlain -> the validator's build-time reject.
        var allPlain = ColumnOverlap.groupByColumn(List.of(plain("film_id"), plain("film_id")));
        assertThat(allPlain).singleElement()
            .matches(OverlapColumn::shared)
            .matches(OverlapColumn::allPlain, "no decode -> allPlain");

        // A plain writer plus a decode on one column: shared && !allPlain -> the runtime agreement check.
        var withDecode = ColumnOverlap.groupByColumn(List.of(plain("film_id"), decode("filmId", "film_id")));
        assertThat(withDecode).singleElement()
            .matches(OverlapColumn::shared)
            .matches(oc -> !oc.allPlain(), "a decode contributor -> not allPlain");
    }

    @Test
    void compositeDecodeContributors_carryTheSlotsTheWriterStated() {
        // A composite decode handed its whole record states contiguous slots, so a contributor's
        // slot reads back the right Record<N> position. A second decode shares the second column
        // (mailbox_id) at a different slot of its own tuple.
        var primary = decode("primary", "address_id", "mailbox_id"); // slots 0, 1
        var sibling = decode("sibling", "mailbox_id");                // slot 0 on its own tuple
        var plan = ColumnOverlap.groupByColumn(List.of(primary, sibling));

        var addressId = plan.stream().filter(oc -> oc.column().sqlName().equals("address_id")).findFirst().orElseThrow();
        assertThat(addressId.contributors()).singleElement()
            .satisfies(c -> {
                assertThat(c.slot()).as("address_id is slot 0 of primary's tuple").isEqualTo(0);
                assertThat(c.writer().label()).isEqualTo("primary");
            });

        var mailboxId = plan.stream().filter(oc -> oc.column().sqlName().equals("mailbox_id")).findFirst().orElseThrow();
        assertThat(mailboxId.shared()).isTrue();
        assertThat(mailboxId.contributors())
            .as("mailbox_id is slot 1 of primary's tuple and slot 0 of sibling's, in writer order")
            .extracting(c -> c.writer().label() + ":" + c.slot())
            .containsExactly("primary:1", "sibling:0");
    }

    @Test
    void partitionedDecodeContributor_keepsItsStatedSlot_notItsListPosition() {
        // The case the stated slot exists for. A straddling cross-table @nodeId reference hands the
        // SET partition one column of a two-column decode record, the one at slot 1. The writer's
        // list has that column at position 0, so a fold that read the position would emit
        // value1() and write the other key column's decoded value into this column.
        var straddler = partialDecode("catalogueId", 1, "catalog_code");
        var plan = ColumnOverlap.groupByColumn(List.of(straddler));

        assertThat(plan).singleElement()
            .satisfies(oc -> assertThat(oc.contributors()).singleElement()
                .satisfies(c -> assertThat(c.slot())
                    .as("the slot is the one the writer stated, not the column's position in its list")
                    .isEqualTo(1)));
    }

    @Test
    void slotColumn_rejectsNegativeSlot() {
        // A negative slot would emit value0(), which does not exist on any jOOQ Record<N>; fail
        // where the fact is minted rather than in the generated source.
        assertThatThrownBy(() -> new ColumnOverlap.SlotColumn(-1, col("film_id")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("slot cannot be negative");
    }
}
