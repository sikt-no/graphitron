package no.sikt.graphitron.rewrite.platformidfixture.tables.records;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Bar;

import org.jooq.impl.UpdatableRecordImpl;

/**
 * Hand-written record class that mimics the footer the custom {@code KjerneJooqGenerator} emits
 * for tables with a legacy composite platform key. Paired with {@link Bar}.
 *
 * <p>Exposes two platform-id accessor pairs: {@code getId()}/{@code setId(String)} for the virtual
 * {@code id} composite and {@code getPersonId()}/{@code setPersonId(String)} for a second virtual
 * ID column. These are what {@code JooqCatalog.hasPlatformIdAccessors} looks for.
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

    public String getId() {
        return "";
    }

    public void setId(String id) {
    }

    public String getPersonId() {
        return "";
    }

    public void setPersonId(String id) {
    }

    public BarRecord() {
        super(Bar.BAR);
    }
}
