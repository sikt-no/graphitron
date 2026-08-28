package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;
import org.jooq.DSLContext;

/**
 * Execution-tier fixture services for polymorphic children under an {@code Outcome} payload:
 * the same address lookup behind two payload backings, a Pojo with a typed hub accessor
 * ({@link OccupantsWithErrorsPayload}) and the hub {@link AddressRecord} itself. Each payload
 * pairs polymorphic children with a {@code WrapperArm} errors field, so the happy path wraps in
 * {@code Outcome.Success} and the thrown {@link OccupantLookupMissingAddressException} routes to
 * {@code Outcome.ErrorList}; the children must resolve on the success arm and render null on the
 * error arm.
 */
public final class OccupantsWithErrorsService {

    private OccupantsWithErrorsService() {}

    /** Pojo-payload arm: wraps the loaded address row behind both hub accessors; throws for the reserved missing id. */
    public static OccupantsWithErrorsPayload byAddressId(Integer addressId, DSLContext dsl) {
        var address = loadAddress(addressId, dsl);
        return new OccupantsWithErrorsPayload(address, java.util.List.of(address));
    }

    /** Record-payload arm: returns the loaded address row itself; throws for the reserved missing id. */
    public static AddressRecord addressById(Integer addressId, DSLContext dsl) {
        return loadAddress(addressId, dsl);
    }

    private static AddressRecord loadAddress(Integer addressId, DSLContext dsl) {
        if (addressId == null || addressId == 999) {
            throw new OccupantLookupMissingAddressException("address " + addressId + " not found");
        }
        return dsl.selectFrom(Tables.ADDRESS)
            .where(Tables.ADDRESS.ADDRESS_ID.eq(addressId))
            .fetchOne();
    }
}
