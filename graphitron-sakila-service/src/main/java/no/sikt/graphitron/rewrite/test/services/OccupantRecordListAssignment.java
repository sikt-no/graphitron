package no.sikt.graphitron.rewrite.test.services;

import org.jooq.UpdatableRecord;

import java.util.List;

/**
 * The list shape of {@link OccupantRecordAssignment}: {@code [ID!]} into
 * {@code List<UpdatableRecord<?>>}, where each element decodes on its own type prefix, so one
 * request may carry a {@code Customer} id and a {@code Staff} id together.
 */
public record OccupantRecordListAssignment(List<UpdatableRecord<?>> occupants) {
}
