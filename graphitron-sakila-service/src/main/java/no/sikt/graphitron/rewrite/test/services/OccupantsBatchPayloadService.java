package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;

import java.util.List;

/**
 * R366 fixture: root {@code @service} that hand-rolls {@link OccupantsBatchPayload} rows whose
 * {@code addresses()} accessor returns {@code List<AddressRecord>}. The classifier walks the
 * accessor reflectively to derive a {@code Reader.AccessorCall} + {@code Cardinality.MANY}
 * polymorphic parent key for the {@code occupants} child; the generator must emit the
 * {@code loader.loadMany(keys, …)} dispatch for the list-cardinality multi-table polymorphic
 * fetcher (see {@link OccupantsBatchPayload}).
 *
 * <p>Compilation-tier fixture: presence of a generated, type-correct fetcher is the whole
 * assertion, so the hand-rolled addresses only need to compile, not round-trip.
 */
public final class OccupantsBatchPayloadService {

    private OccupantsBatchPayloadService() {}

    /**
     * Returns two payloads carrying distinct address PKs. The union of accessor-derived keys
     * across both parents drives the {@code loader.loadMany} dispatch for the occupants lookup.
     */
    public static List<OccupantsBatchPayload> occupantsBatch() {
        AddressRecord a1 = new AddressRecord();
        a1.setAddressId(1);
        AddressRecord a2 = new AddressRecord();
        a2.setAddressId(2);
        AddressRecord a3 = new AddressRecord();
        a3.setAddressId(3);
        return List.of(
            new OccupantsBatchPayload(List.of(a1, a2)),
            new OccupantsBatchPayload(List.of(a3))
        );
    }
}
