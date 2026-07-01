package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.StorageBinRecord;

/**
 * R322 execution-tier fixture: a jOOQ {@link StorageBinRecord} bound directly as a {@code @service}
 * input param, whose input carries <em>two writers landing on the same column</em> ({@code bin_id}) — a
 * same-table identity {@code @nodeId(typeName: "StorageBin")} decode and a plain {@code @field} bound to
 * {@code bin_id}. The generated {@code createStorageBinRecord} helper emits the R322 value-agreement
 * check ({@code NodeIdEncoder.requireColumnAgreement}) before the loads: the present writers are coerced
 * through {@code bin_id}'s integer {@code DataType} and must resolve to the same value, else it throws
 * {@code GraphqlErrorException} and the service body never runs.
 *
 * <p>{@link #agreeStorageBin} reports the agreed value off the constructed record without touching the
 * database — the agreement fires inside the helper (before the service), and {@code bin_id} is the only
 * column the fixture writes, so no INSERT is needed to observe the contract. The execution tier drives
 * three rows of the matrix off one input: agreement on a <em>format-variant</em> wire value ({@code "01"}
 * for the plain field, which {@code String.valueOf} would false-disagree with the decoded {@code "1"} but
 * the integer-column coercion collapses to the same key), disagreement (distinct values throw), and the
 * presence guard (an omitted nullable plain field leaves the lone decode and does not throw).
 */
public final class StorageBinRecordAgreementService {

    private StorageBinRecordAgreementService() {}

    /** Reports the agreed {@code bin_id} off the record the agreement-checked helper constructed. */
    public static String agreeStorageBin(StorageBinRecord in) {
        return "bin_id=" + in.getBinId();
    }
}
