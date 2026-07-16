package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link ParentRowDemand#polymorphicParentRowColumns}: the parent-row
 * demand a multi-table polymorphic child field ({@link ChildField.InterfaceField} /
 * {@link ChildField.UnionField}) surfaces onto its parent's {@code $fields} projection.
 *
 * <p>The accessor forks on the field's cardinality and, at single cardinality, enumerates the
 * union across participants of what {@code MultiTablePolymorphicEmitter.singleBranchCorrelationWhere}
 * reads off the parent row. Pinned across the three correlation shapes:
 * {@link ParticipantCorrelation.KeyTupleWhere}, a {@link ParticipantCorrelation.JoinedCorrelation}
 * FK hop-0 ({@link On.ColumnPairs}), and a {@code JoinedCorrelation} condition hop-0
 * ({@link On.Predicate}). At list / connection cardinality the demand is the parent bound key
 * regardless of correlation shape (what the batched key extraction reads).
 */
@UnitTier
class ParentRowDemandTest {

    private static final ColumnRef CUSTOMER_ID = TestFixtures.col("customer_id", "CUSTOMER_ID", "java.lang.Integer");
    private static final ColumnRef ADDRESS_ID = TestFixtures.col("address_id", "ADDRESS_ID", "java.lang.Integer");
    private static final ColumnRef STORE_ID = TestFixtures.col("store_id", "STORE_ID", "java.lang.Integer");

    private static final TableRef ADDRESS = TestFixtures.tableRef(
        "address", "ADDRESS", "Address", List.of(ADDRESS_ID));
    private static final TableRef STORE = TestFixtures.tableRef(
        "store", "STORE", "Store", List.of(STORE_ID));
    private static final TableRef CUSTOMER = TestFixtures.tableRef(
        "customer", "CUSTOMER", "Customer", List.of(CUSTOMER_ID));

    /** The parent (customer) IS the source; its bound key is the customer PK. */
    private static final SourceKey PARENT_SOURCE_KEY =
        TestFixtures.polymorphicRowParentSourceKey(List.of(CUSTOMER_ID));

    /** KeyTupleWhere whose parent side is the parent's own FK column (customer.address_id). */
    private static ParticipantCorrelation keyTupleParentHoldsFk() {
        return TestFixtures.participantFkPath(List.of(ADDRESS_ID), List.of(ADDRESS_ID));
    }

    /** JoinedCorrelation whose FK hop-0 reads customer.store_id as its parent side. */
    private static ParticipantCorrelation joinedFkHopParentHoldsFk() {
        var hop = TestFixtures.fkJoin(
            TestFixtures.foreignKeyRef("customer_store_id_fkey"),
            CUSTOMER, List.of(STORE_ID),
            STORE, List.of(STORE_ID),
            null, "store_0");
        return new ParticipantCorrelation.JoinedCorrelation(List.of(hop));
    }

    /** JoinedCorrelation whose hop-0 is an authored condition (correlates on the bound key). */
    private static ParticipantCorrelation joinedConditionHop() {
        var hop = TestFixtures.conditionJoin(
            TestFixtures.staticOnlyMethodRef("no.sikt.graphitron.rewrite.TestConditionStub", "join",
                ClassName.get("org.jooq", "Condition")),
            ADDRESS, "address_0");
        return new ParticipantCorrelation.JoinedCorrelation(List.of(hop));
    }

    @Test
    void single_keyTupleWhere_demandsParentSideCorrelationColumns() {
        var demand = ParentRowDemand.polymorphicParentRowColumns(
            false, Map.of("AddressP", keyTupleParentHoldsFk()), PARENT_SOURCE_KEY);
        // The parent side (slot.sourceSide()) is customer.address_id — the FK column the single-fetch
        // WHERE reads off parentRecord, not the parent's bound key.
        assertThat(demand).containsExactly(ADDRESS_ID);
    }

    @Test
    void single_joinedCorrelationFkHop0_demandsHopSourceSideColumns() {
        var demand = ParentRowDemand.polymorphicParentRowColumns(
            false, Map.of("StoreP", joinedFkHopParentHoldsFk()), PARENT_SOURCE_KEY);
        assertThat(demand).containsExactly(STORE_ID);
    }

    @Test
    void single_joinedCorrelationConditionHop0_demandsParentBoundKey() {
        var demand = ParentRowDemand.polymorphicParentRowColumns(
            false, Map.of("Cond", joinedConditionHop()), PARENT_SOURCE_KEY);
        // A condition hop-0 correlates on the parent's bound key (parentKeyBoundWhere), so the demand
        // is the parent source key, not an off-key correlation column.
        assertThat(demand).containsExactly(CUSTOMER_ID);
    }

    @Test
    void single_unionsAcrossParticipants() {
        var demand = ParentRowDemand.polymorphicParentRowColumns(
            false,
            Map.of("AddressP", keyTupleParentHoldsFk(), "StoreP", joinedFkHopParentHoldsFk()),
            PARENT_SOURCE_KEY);
        assertThat(demand).containsExactlyInAnyOrder(ADDRESS_ID, STORE_ID);
    }

    @Test
    void list_demandsParentBoundKey_regardlessOfCorrelationShape() {
        // The batched key extraction reads parentSourceKey.columns() off env.getSource(); the
        // per-participant correlation shape does not change the demand.
        var demand = ParentRowDemand.polymorphicParentRowColumns(
            true,
            Map.of("AddressP", keyTupleParentHoldsFk(), "StoreP", joinedFkHopParentHoldsFk()),
            PARENT_SOURCE_KEY);
        assertThat(demand).containsExactly(CUSTOMER_ID);
    }
}
