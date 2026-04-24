package no.sikt.graphitron.rewrite.platformidfixture.tables.records;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Bar;

import org.jooq.impl.UpdatableRecordImpl;

/**
 * Hand-written record class paired with {@link Bar}. Holds only the real {@code name} column;
 * node-identity is read from the {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} constants
 * on {@link Bar} via {@link no.sikt.graphitron.rewrite.JooqCatalog#nodeIdMetadata}.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class BarRecord extends UpdatableRecordImpl<BarRecord> {

    private static final long serialVersionUID = 1L;

    public void setName(String value) {
        set(0, value);
    }

    public String getName() {
        return (String) get(0);
    }

    public BarRecord() {
        super(Bar.BAR);
    }
}
