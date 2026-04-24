package no.sikt.graphitron.rewrite.platformidfixture.tables.records;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Qux;

import org.jooq.impl.UpdatableRecordImpl;

/**
 * Counterpart to {@link no.sikt.graphitron.rewrite.platformidfixture.tables.records.BarRecord} that
 * deliberately lacks platform-id accessors. Used by pipeline tests to exercise the fallback-miss
 * path (column absent AND no {@code get*Id}/{@code set*Id} accessors on the record).
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class QuxRecord extends UpdatableRecordImpl<QuxRecord> {

    private static final long serialVersionUID = 1L;

    public void setName(String value) {
        set(0, value);
    }

    public String getName() {
        return (String) get(0);
    }

    public QuxRecord() {
        super(Qux.QUX);
    }
}
