package no.sikt.graphitron.rewrite.test.services;

import org.jooq.TableRecord;

/**
 * {@link OccupantRecordAssignment} with the member typed {@code TableRecord<?>}, a record supertype
 * every table's record has, a table without a primary key included.
 */
public record OccupantTableRecordAssignment(TableRecord<?> occupant) {
}
