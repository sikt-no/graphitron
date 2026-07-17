package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParticipantCorrelation;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage: a multi-table polymorphic child field correlating through a foreign key
 * held on the <em>parent's</em> table (parent-holds-FK), so the parent-side correlation column is
 * the FK column, not the parent's primary key.
 *
 * <ul>
 *   <li>At single cardinality the field classifies (gap A is fixed by force-projecting the
 *       parent-side column via the {@code ParentRowDemand} capability); the resolved
 *       {@link ParticipantCorrelation.KeyTupleWhere} carries the parent's FK column as its
 *       {@code sourceSide()}.</li>
 *   <li>At list / connection cardinality the field is rejected DEFERRED (gap C): the batched
 *       forms alias {@code parentInput} to the bound-key names only, so a
 *       parent-holds-FK participant would emit {@code parentInput.field("<fk-col>")} returning null.
 *       Covered on the auto-discovery arm, an explicit {@code @referenceFor} route, and a multi-hop
 *       route whose hop-0 FK lives on the parent table. A list-cardinality child-holds-FK field
 *       (parent side = parent key) still classifies.</li>
 * </ul>
 *
 * <p>Catalog-identity orientation notes (sakila): {@code customer.address_id -> address} and
 * {@code customer.store_id -> store} are parent-holds-FK (the FK column lives on customer, the
 * parent); {@code rental.customer_id -> customer} and {@code payment.customer_id -> customer} are
 * child-holds-FK (the FK column lives on the participant).
 */
@PipelineTier
class MultiTablePolymorphicParentHoldsFkPipelineTest {

    // ===== Single cardinality: parent-holds-FK classifies, off-key parent side =====

    @Test
    void crossTableParentHoldsFk_singleCardinality_classifiesWithOffKeyParentSide() {
        // customer holds address_id (FK to address) and store_id (FK to store); a single-valued
        // polymorphic field navigates to those participants. The parent side of each correlation is
        // the FK column on customer, not customer's PK (customer_id).
        var schema = TestSchemaHelper.buildSchema("""
            interface CustRef { rowId: Int }
            type AddressP implements CustRef @table(name: "address") { rowId: Int @field(name: "address_id") }
            type StoreP implements CustRef @table(name: "store") { rowId: Int @field(name: "store_id") }
            type Customer @table(name: "customer") {
              ref: CustRef
            }
            type Query { customer: Customer }
            """);
        var field = (ChildField.InterfaceField) schema.field("Customer", "ref");

        var addr = field.participantJoinPaths().get("AddressP");
        assertThat(addr).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
        var addrSlots = ((ParticipantCorrelation.KeyTupleWhere) addr).slots();
        assertThat(addrSlots).hasSize(1);
        // Parent side is customer.address_id (the FK column), participant side is address.address_id.
        assertThat(addrSlots.get(0).sourceSide().sqlName()).isEqualTo("address_id");
        assertThat(addrSlots.get(0).targetSide().sqlName()).isEqualTo("address_id");

        var store = field.participantJoinPaths().get("StoreP");
        assertThat(store).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
        var storeSlots = ((ParticipantCorrelation.KeyTupleWhere) store).slots();
        assertThat(storeSlots.get(0).sourceSide().sqlName()).isEqualTo("store_id");

        // The capability surfaces the off-key parent-side columns onto the parent projection (gap A).
        assertThat(field.parentRowColumns().stream().map(c -> c.sqlName()).toList())
            .containsExactlyInAnyOrder("address_id", "store_id");
    }

    // ===== List cardinality: parent-holds-FK rejects DEFERRED (gap C) =====

    @Test
    void listCardinalityParentHoldsFk_autoDiscovery_rejectsDeferred() {
        // A list field. AddressP is parent-holds-FK (customer.address_id) -> deferred. RentalP is
        // child-holds-FK (rental.customer_id -> customer) and classifies, so the aggregate carries
        // exactly the one deferred rejection, returned verbatim with the deferred-plan slug.
        var schema = TestSchemaHelper.buildSchema("""
            interface CustRef { rowId: Int }
            type AddressP implements CustRef @table(name: "address") { rowId: Int @field(name: "address_id") }
            type RentalP implements CustRef @table(name: "rental") { rowId: Int @field(name: "rental_id") }
            type Customer @table(name: "customer") {
              refs: [CustRef]
            }
            type Query { customer: Customer }
            """);
        var rejection = rejectionOf(schema.field("Customer", "refs"));
        assertThat(rejection).isInstanceOf(Rejection.Deferred.class);
        assertThat(rejection.message())
            .contains("AddressP")
            .contains("address_id")
            .contains("foreign key held on the parent");
    }

    @Test
    void listCardinalityParentHoldsFk_explicitReferenceFor_rejectsDeferred() {
        var schema = TestSchemaHelper.buildSchema("""
            interface CustRef { rowId: Int }
            type AddressP implements CustRef @table(name: "address") { rowId: Int @field(name: "address_id") }
            type RentalP implements CustRef @table(name: "rental") { rowId: Int @field(name: "rental_id") }
            type Customer @table(name: "customer") {
              refs: [CustRef] @referenceFor(type: "AddressP", path: [{key: "customer_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """);
        var rejection = rejectionOf(schema.field("Customer", "refs"));
        assertThat(rejection).isInstanceOf(Rejection.Deferred.class);
        assertThat(rejection.message()).contains("AddressP").contains("address_id");
    }

    @Test
    void listCardinalityMultiHop_hop0FkOnParentTable_rejectsDeferred() {
        // The two-hop route customer -> address -> city has its hop-0 FK (customer.address_id) on the
        // parent table: the batched JOIN parentInput predicate would look up the off-key parent side.
        // RentalP auto-discovers and classifies, so the deferred CityP rejection is returned verbatim.
        var schema = TestSchemaHelper.buildSchema("""
            interface CustRef { rowId: Int }
            type CityP implements CustRef @table(name: "city") { rowId: Int @field(name: "city_id") }
            type RentalP implements CustRef @table(name: "rental") { rowId: Int @field(name: "rental_id") }
            type Customer @table(name: "customer") {
              refs: [CustRef] @referenceFor(type: "CityP", path: [{key: "customer_address_id_fkey"}, {key: "address_city_id_fkey"}])
            }
            type Query { customer: Customer }
            """);
        var rejection = rejectionOf(schema.field("Customer", "refs"));
        assertThat(rejection).isInstanceOf(Rejection.Deferred.class);
        assertThat(rejection.message()).contains("CityP").contains("address_id");
    }

    @Test
    void listCardinalityChildHoldsFk_stillClassifies() {
        // Child-holds-FK: rental.customer_id -> customer and payment.customer_id -> customer. A list
        // field collects the children pointing back; the parent side is customer's PK (on-key), so the
        // gap C guard does not fire.
        var schema = TestSchemaHelper.buildSchema("""
            interface CustRef { rowId: Int }
            type RentalP implements CustRef @table(name: "rental") { rowId: Int @field(name: "rental_id") }
            type PaymentP implements CustRef @table(name: "payment") { rowId: Int @field(name: "payment_id") }
            type Customer @table(name: "customer") {
              refs: [CustRef]
            }
            type Query { customer: Customer }
            """);
        var field = (ChildField.InterfaceField) schema.field("Customer", "refs");
        var rental = field.participantJoinPaths().get("RentalP");
        assertThat(rental).isInstanceOf(ParticipantCorrelation.KeyTupleWhere.class);
        // Parent side is customer.customer_id (the parent key), participant side is rental.customer_id.
        assertThat(((ParticipantCorrelation.KeyTupleWhere) rental).slots().get(0).sourceSide().sqlName())
            .isEqualTo("customer_id");
        // List-cardinality batched key extraction reads the parent bound key.
        assertThat(field.parentRowColumns().stream().map(c -> c.sqlName()).toList())
            .containsExactly("customer_id");
    }

    private static Rejection rejectionOf(no.sikt.graphitron.rewrite.model.GraphitronField field) {
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) field).rejection();
    }
}
