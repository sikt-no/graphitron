package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.CustomerRecord;

/**
 * Fact fixture: a {@code @service} input bean whose member is typed as one member's own record,
 * {@code CustomerRecord}, beside an SDL field naming the {@code AddressOccupant} union, so a
 * {@code Staff} id has nowhere to land.
 */
public record OccupantCustomerAssignment(CustomerRecord occupant) {
}
