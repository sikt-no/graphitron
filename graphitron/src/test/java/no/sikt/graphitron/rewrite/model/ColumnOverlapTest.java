package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.model.ColumnOverlap.ColumnWriter;
import no.sikt.graphitron.rewrite.model.ColumnOverlap.OverlapColumn;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural invariant tests for the {@link ColumnOverlap#groupByColumn} primitive, the one
 * grouping the six DML mutation write-path sites read.
 *
 * <p>This is the anti-drift assertion the lift rests on: the <em>same</em> {@link ColumnWriter} list
 * yields both the predicate the validator rejects on ({@code shared() && allPlain()}) and the
 * predicate the emitters trigger value-agreement on ({@code shared()}). Before R356 those were two
 * hand-rolled walks that could diverge; here they read one fold.
 */
@UnitTier
class ColumnOverlapTest {

    private static ColumnRef col(String sqlName) {
        return new ColumnRef(sqlName, sqlName.toUpperCase(), "java.lang.Integer");
    }

    /** A test writer over a fixed column list; {@code decode} flags whether it is a {@code @nodeId} decode. */
    private record TestWriter(List<ColumnRef> targetColumns, boolean decode, String label)
            implements ColumnWriter {}

    private static TestWriter plain(String sqlName) {
        return new TestWriter(List.of(col(sqlName)), false, sqlName);
    }

    private static TestWriter decode(String label, String... sqlNames) {
        return new TestWriter(List.of(sqlNames).stream().map(ColumnOverlapTest::col).toList(), true, label);
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
    void compositeDecodeContributors_carrySlotsIndexingTargetColumnsInRecordOrder() {
        // The load-bearing invariant: a composite decode's contributors carry slots that index its
        // targetColumns() in decode-record order. A second decode shares the second column (mailbox_id),
        // so its slot there must read back the right Record<N> position.
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
}
