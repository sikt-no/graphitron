package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;
import org.jooq.DSLContext;

/**
 * Execution-tier fixture: a record-backed (Pojo) parent that exposes a single typed jOOQ
 * {@link AddressRecord} accessor, carrying a <em>single-cardinality</em> polymorphic child field
 * ({@code firstOccupant: AddressOccupant}).
 *
 * <p>The carrier {@link AddressOccupantCarrier} is a plain Java record (not a jOOQ {@code Record},
 * not {@code @record}-annotated), so {@code AddressOccupantCarrier} binds as a {@code PojoResultType}.
 * Its {@code address()} accessor returns the hub {@link AddressRecord}; the schema field
 * {@code firstOccupant} remaps to it via {@code @field(name: "address")}. The single-cardinality
 * multi-table polymorphic fetcher therefore takes the record-backed-parent arm:
 * it binds {@code parentRecord} to the accessor's returned hub record (rather than casting
 * {@code env.getSource()} to a jOOQ {@code Record}) and reads {@code address_id} off it to
 * correlate the {@code Customer}/{@code Staff} stage-1 branches.
 *
 * <p>Single cardinality picks the first occupant by the stage-1 union's sort order, so the test
 * pins which concrete type and row that is for a known address.
 */
public final class AddressOccupantCarrierService {

    private AddressOccupantCarrierService() {}

    /** Loads the address row and wraps it; {@code null} for an unknown id (no hub, no occupant). */
    public static AddressOccupantCarrier byId(Integer addressId, DSLContext dsl) {
        if (addressId == null) return null;
        AddressRecord a = dsl.selectFrom(Tables.ADDRESS)
            .where(Tables.ADDRESS.ADDRESS_ID.eq(addressId))
            .fetchOne();
        return a == null ? null : new AddressOccupantCarrier(a);
    }
}
