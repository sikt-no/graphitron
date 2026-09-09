package no.sikt.graphitron.rewrite.test.services;

import org.jooq.UpdatableRecord;

/**
 * Execution-tier fixture: a {@code @service} input bean whose member is typed as a jOOQ record
 * <em>supertype</em>, backed by an {@code ID! @nodeId(typeName: "AddressOccupant")} SDL field naming
 * the union rather than one node type. Both members' tables have a primary key, so
 * {@code UpdatableRecord<?>} is the narrowest type both {@code CustomerRecord} and
 * {@code StaffRecord} are, and it is what the generated container helper returns.
 */
public record OccupantRecordAssignment(UpdatableRecord<?> occupant) {
}
