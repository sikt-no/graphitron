package no.sikt.graphitron.rewrite.platformidfixture.tables.records;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Baz;

import org.jooq.impl.UpdatableRecordImpl;

/**
 * Record for the synthetic {@code baz} table used in NodeIdReferenceField pipeline tests.
 * {@link Baz} carries {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} metadata and has a
 * FK from {@link no.sikt.graphitron.rewrite.platformidfixture.tables.Bar bar.id_1 → baz.id}.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class BazRecord extends UpdatableRecordImpl<BazRecord> {

    private static final long serialVersionUID = 1L;

    public void setId(String value) {
        set(0, value);
    }

    public String getId() {
        return (String) get(0);
    }

    public BazRecord() {
        super(Baz.BAZ);
    }
}
