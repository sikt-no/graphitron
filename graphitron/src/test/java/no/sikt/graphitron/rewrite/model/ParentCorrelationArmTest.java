package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the parent-anchor arm {@link ParentCorrelation.OnParentJoin} (the
 * generalization of the former {@code OnConditionJoin}) and the batch-grain projection
 * {@link ParentCorrelation#parentKeyColumns()}.
 *
 * <p>The arm now carries both {@link On.ColumnPairs} (a filter-carrying FK hop) and
 * {@link On.Predicate} (a condition-join hop) first hops, so the compact-constructor guard shifts
 * from "must be On.Predicate" to "must not be On.Lateral, must have a parent table". The grain
 * accessor is what makes "parent-PK grain iff parent-anchor topology" structural, so it is pinned
 * per arm.
 */
@UnitTier
class ParentCorrelationArmTest {

    private static final ColumnRef CUSTOMER_ID = TestFixtures.col("customer_id", "CUSTOMER_ID", "java.lang.Integer");
    private static final ColumnRef ADDRESS_ID = TestFixtures.col("address_id", "ADDRESS_ID", "java.lang.Integer");

    private static final TableRef CUSTOMER = TestFixtures.tableRef(
        "customer", "CUSTOMER", "Customer", List.of(CUSTOMER_ID));
    private static final TableRef ADDRESS = TestFixtures.tableRef(
        "address", "ADDRESS", "Address", List.of(ADDRESS_ID));

    private static JoinConditionRef stubFilter() {
        return new JoinConditionRef(TestFixtures.staticOnlyMethodRef(
            "no.sikt.graphitron.rewrite.TestConditionStub", "join",
            ClassName.get("org.jooq", "Condition")));
    }

    /** customer.address_id -> address.address_id, parent-holds-FK, carrying a hop-0 filter. */
    private static JoinStep.Hop filteredFkHop() {
        return TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("customer_address_id_fkey"),
            CUSTOMER, List.of(ADDRESS_ID),
            ADDRESS, List.of(ADDRESS_ID),
            stubFilter(), "address_0");
    }

    /** Same FK hop with no per-hop filter (the plain OnFkSlots shape). */
    private static JoinStep.Hop plainFkHop() {
        return TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("customer_address_id_fkey"),
            CUSTOMER, List.of(ADDRESS_ID),
            ADDRESS, List.of(ADDRESS_ID),
            null, "address_0");
    }

    @Test
    void onParentJoin_acceptsFilteredColumnPairsHop() {
        var pj = new ParentCorrelation.OnParentJoin(filteredFkHop(), CUSTOMER);
        assertThat(pj.firstHop().on()).isInstanceOf(On.ColumnPairs.class);
        assertThat(pj.parentTable()).isSameAs(CUSTOMER);
    }

    @Test
    void onParentJoin_acceptsConditionPredicateHop() {
        var conditionHop = TestFixtures.conditionJoin(
            TestFixtures.staticOnlyMethodRef("no.sikt.graphitron.rewrite.TestConditionStub", "join",
                ClassName.get("org.jooq", "Condition")),
            ADDRESS, "address_0");
        var pj = new ParentCorrelation.OnParentJoin(conditionHop, CUSTOMER);
        assertThat(pj.firstHop().on()).isInstanceOf(On.Predicate.class);
    }

    @Test
    void onParentJoin_rejectsNullParentTable() {
        var hop = filteredFkHop();
        assertThatThrownBy(() -> new ParentCorrelation.OnParentJoin(hop, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("parentTable");
    }

    @Test
    void parentKeyColumns_onParentJoin_isParentPrimaryKey() {
        // The parent-anchor arm keys the batch on the parent PK, not the FK slot (address_id).
        var pj = new ParentCorrelation.OnParentJoin(filteredFkHop(), CUSTOMER);
        assertThat(pj.parentKeyColumns()).containsExactly(CUSTOMER_ID);
    }

    @Test
    void parentKeyColumns_onFkSlots_isFirstHopSourceColumns() {
        // The filter-less FK arm keys on the first hop's source-side columns (the FK-holder side).
        var fk = new ParentCorrelation.OnFkSlots(plainFkHop());
        assertThat(fk.parentKeyColumns()).containsExactly(ADDRESS_ID);
    }

    @Test
    void parentKeyOwnerTable_onParentJoin_isParentTable() {
        var pj = new ParentCorrelation.OnParentJoin(filteredFkHop(), CUSTOMER);
        assertThat(pj.parentKeyOwnerTable()).isSameAs(CUSTOMER);
    }
}
