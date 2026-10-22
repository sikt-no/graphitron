package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.AddressRecord;

import java.util.List;

/**
 * The payload behind {@code OccupantsNonNullWithErrorsPayload}: the {@code addresses()} hub
 * accessor alone, feeding a non-null batched polymorphic list beside a {@code WrapperArm} errors
 * field.
 */
public record OccupantsListWithErrorsPayload(List<AddressRecord> addresses) {}
