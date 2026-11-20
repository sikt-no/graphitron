package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Record;

/**
 * {@link OccupantRecordAssignment} with the member typed bare {@code Record}, the widest record
 * supertype there is.
 */
public record OccupantPlainRecordAssignment(Record occupant) {
}
