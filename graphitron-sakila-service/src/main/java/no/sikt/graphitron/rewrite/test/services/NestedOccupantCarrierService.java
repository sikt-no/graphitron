package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;
import org.jooq.DSLContext;

/**
 * Compilation-tier fixture (producer #2): a record-backed parent whose backing class is a
 * <em>nested</em> record ({@link NestedOccupantCarrierHolder.Carrier}), carrying a
 * single-cardinality polymorphic child ({@code firstOccupant: AddressOccupant}).
 *
 * <p>Structurally identical to {@link AddressOccupantCarrierService} except the carrier is nested,
 * so its binary name carries a {@code $}. This is the only compiling witness for
 * {@code FieldBuilder.derivePolymorphicHubSource} → {@code MultiTablePolymorphicEmitter
 * .buildScalarPerParentFetcher} on a nested carrier: the emitted cast to the parent backing class
 * must spell {@code Outer.Nested}, not {@code Outer$Nested}, or {@code mvn install -Plocal-db} fails
 * at the {@code graphitron-sakila-example} compile gate.
 */
public final class NestedOccupantCarrierService {

    private NestedOccupantCarrierService() {}

    /** Loads the address row and wraps it in the nested carrier; {@code null} for an unknown id. */
    public static NestedOccupantCarrierHolder.Carrier byId(Integer addressId, DSLContext dsl) {
        if (addressId == null) return null;
        AddressRecord a = dsl.selectFrom(Tables.ADDRESS)
            .where(Tables.ADDRESS.ADDRESS_ID.eq(addressId))
            .fetchOne();
        return a == null ? null : new NestedOccupantCarrierHolder.Carrier(a);
    }
}
