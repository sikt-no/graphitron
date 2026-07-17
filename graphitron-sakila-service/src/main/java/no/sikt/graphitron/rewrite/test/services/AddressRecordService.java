package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.AddressRecord;
import org.jooq.DSLContext;

/**
 * Execution-tier fixture: a jOOQ {@link AddressRecord} bound directly as a {@code @service} input
 * param, populated from a <em>nullable same-table identity</em> {@code @nodeId(typeName: "Address")} —
 * Address's own table, so the decode resolves to the record's own primary key {@code address_id} (the
 * {@code X.table == record.table} arm, here exercised with a nullable {@code ID}).
 *
 * <p>This is the end-to-end pin for D4's nullable-same-table-identity behavior change. Binding a jOOQ
 * record as a service param previously always threw on a null / omitted identity (whether {@code ID!}
 * or {@code ID}); a nullable identity now skips-when-omitted, so a DB-assignable serial PK can be left
 * unset for the service-owned INSERT to assign. {@link #upsertAddress} branches on the per-column {@code changed} flag:
 * <ul>
 *   <li><b>omitted</b> ({@code addressId} absent → {@code address_id} {@code changed=false}): the
 *       service owns the INSERT (and thus the DML). It fills the table's other
 *       {@code NOT NULL} columns and inserts; the database assigns the serial {@code address_id}, which
 *       jOOQ refreshes back into the record — proving an omitted nullable identity does not throw and is
 *       left for the DB to populate.</li>
 *   <li><b>set</b> ({@code addressId} present → decoded onto {@code address_id}): the decoded Address id
 *       lands on the PK (the update path); the service reads it straight back.</li>
 * </ul>
 *
 * <p>Address is used rather than the spec's {@code FilmRecord} example because it is a reachable
 * {@code @node} with a serial DB-assignable PK whose record is not otherwise a {@code @service} param:
 * a second {@code FilmRecord}-backed input would collide with {@code ModifyFilmRecordInput} on the
 * per-fetcher {@code create<Record>} dedup (keyed by record class, {@code putIfAbsent}).
 */
public final class AddressRecordService {

    private AddressRecordService() {}

    /**
     * Upserts on the nullable same-table identity {@code address_id}. Omitted → the PK is left unset and
     * the service-owned INSERT lets the database assign it (returns whether the DB populated it); set →
     * the decoded id is already on the PK (returns its value). One method, two paths, split on the jOOQ
     * {@code changed} flag — the only tier that can observe {@code changed=false} exclusion.
     */
    public static String upsertAddress(AddressRecord in, DSLContext dsl) {
        if (in.touched(Tables.ADDRESS.ADDRESS_ID)) {
            // set: the decoded same-table identity landed on the record's own PK (the update path).
            return "set: pk=" + in.getAddressId();
        }
        // omitted: address_id stayed unset (changed=false), so the service owns the INSERT and the
        // database assigns the serial PK. Fill the other NOT NULL columns (city_id 1 is seeded) so the
        // row is valid, then read the DB-assigned PK back off the refreshed record.
        in.setAddress("record-service fixture address");
        in.setDistrict("fixture district");
        in.setCityId(1);
        in.attach(dsl.configuration());
        in.insert();
        return "omitted: pkAssignedByDb=" + (in.getAddressId() != null);
    }
}
