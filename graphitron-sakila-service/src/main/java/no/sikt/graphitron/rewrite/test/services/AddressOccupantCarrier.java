package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;

/**
 * R367 fixture: a record-backed (Pojo) carrier holding the hub {@link AddressRecord}. Returned by
 * {@link AddressOccupantCarrierService#byId}, so the SDL {@code AddressOccupantCarrier} type binds
 * as a {@code PojoResultType}. The {@code address()} accessor is the typed hub accessor the
 * single-cardinality polymorphic {@code firstOccupant} child derives its hub from.
 *
 * <p>Top-level (not a nested record) so its binary name has no {@code $} segment: the @service
 * payload-type binding compares the method's declared return type by name.
 */
public record AddressOccupantCarrier(AddressRecord address) {}
