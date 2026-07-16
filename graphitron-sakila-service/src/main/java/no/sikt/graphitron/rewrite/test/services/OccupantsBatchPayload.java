package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;

import java.util.List;

/**
 * Fixture: free-form {@code @record} payload exposing a typed zero-arg accessor returning
 * {@code List<AddressRecord>}, paired with a <em>list-cardinality multi-table polymorphic</em>
 * child ({@code occupants: [AddressOccupant!]!}, the {@code Customer | Staff} union, both FK back
 * to {@code address}). The classifier resolves the polymorphic hub ({@code address}) from the
 * {@code addresses()} accessor and produces a {@code SourceKey} with
 * {@code Reader.AccessorCall} + {@code Cardinality.MANY}.
 *
 * <p>That MANY reader is the path the fix targets: {@code MultiTablePolymorphicEmitter}'s
 * {@code buildBatchedListFetcher} used to emit {@code loader.load(key, env)} against the
 * out-of-scope loop-local {@code key} the MANY key-extraction declares (it declares a
 * {@code List<RecordN<…>> keys}), so the generated fetcher failed javac with
 * "cannot find symbol: variable key". The compilation tier is the assertion: this payload makes
 * the generator emit the {@code loader.loadMany(keys, …)} dispatch, and {@code mvn compile} on
 * {@code graphitron-sakila-example} fails if the load site regresses to {@code load(key)}.
 */
public record OccupantsBatchPayload(List<AddressRecord> addresses) {}
