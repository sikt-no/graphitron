package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;

import java.util.List;

/**
 * Fixture: Pojo payload (plain Java record, {@code PojoResultType}) exposing typed hub
 * accessors beside a {@code WrapperArm} errors field. Its polymorphic children (both over the
 * {@code AddressOccupant = Customer | Staff} union) receive an {@code Outcome} as
 * {@code env.getSource()}, so their fetchers must narrow {@code Outcome.Success} before reading
 * the hub off the accessor — the reporter's shape in
 * https://github.com/sikt-no/graphitron/issues/526. The classifier pairs field cardinality with
 * accessor cardinality, so the single-valued child binds {@code address()} (accessor arity ONE,
 * the per-parent inline fetcher) and the list child binds {@code addresses()} (arity MANY, the
 * {@code loadMany} DataLoader dispatch); both hold the same hub row.
 */
public record OccupantsWithErrorsPayload(AddressRecord address, List<AddressRecord> addresses) {}
