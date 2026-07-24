package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;
import org.jooq.DSLContext;

/**
 * Execution-tier fixture: a jOOQ {@link AddressRecord} bound directly as a {@code @service} input
 * param, populated from a <em>nullable same-table identity</em> {@code @nodeId(typeName: "Address")};
 * Address's own table, so the decode lands on the record's own primary key {@code address_id}.
 *
 * <p>Pins the nullable same-table identity contract end to end: an omitted nullable identity is
 * skipped ({@code address_id} keeps {@code changed=false}) instead of rejected, leaving a
 * DB-assignable serial PK unset for the service-owned INSERT to assign; a set identity decodes onto
 * the PK. Driven by the {@code upsertAddress} mutation tests in the sakila-example
 * {@code GraphQLQueryTest}.
 *
 * <p>Address rather than {@code FilmRecord} because Address is a reachable {@code @node} with a
 * serial DB-assignable PK whose record is not otherwise a {@code @service} param: a second
 * {@code FilmRecord}-backed input would collide with {@code ModifyFilmRecordInput} on the
 * per-fetcher {@code create<Record>} dedup (keyed by record class, {@code putIfAbsent}).
 */
public final class AddressRecordService {

    private AddressRecordService() {}

    /**
     * Splits on the jOOQ per-column {@code changed} flag for {@code address_id}: the service tier
     * is the only place {@code changed=false} exclusion of an omitted identity is observable.
     */
    public static String upsertAddress(AddressRecord in, DSLContext dsl) {
        if (in.touched(Tables.ADDRESS.ADDRESS_ID)) {
            return "set: pk=" + in.getAddressId();
        }
        // Fill the other NOT NULL columns (city_id 1 is seeded) and insert; jOOQ refreshes the
        // DB-assigned serial PK back into the record.
        in.setAddress("record-service fixture address");
        in.setDistrict("fixture district");
        in.setCityId(1);
        in.attach(dsl.configuration());
        in.insert();
        return "omitted: pkAssignedByDb=" + (in.getAddressId() != null);
    }
}
