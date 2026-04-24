package no.sikt.graphitron.rewrite.platformidfixture.tables;

import no.sikt.graphitron.rewrite.platformidfixture.Public;
import no.sikt.graphitron.rewrite.platformidfixture.tables.records.QuxRecord;

import org.jooq.Name;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableOptions;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;

/**
 * Fixture table that exposes deliberately malformed {@code __NODE_TYPE_ID} /
 * {@code __NODE_KEY_COLUMNS} constants. Used to verify that
 * {@link no.sikt.graphitron.rewrite.JooqCatalog#nodeIdMetadataDiagnostic} returns a non-empty
 * reason when the constants are present but fail validation.
 *
 * <p>{@code __NODE_TYPE_ID} is an empty string — the first sanity check rejects it.
 * {@code __NODE_KEY_COLUMNS} is also malformed (empty array) but is never reached because
 * validation short-circuits at the typeId check.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class MalformedBar extends TableImpl<QuxRecord> {

    private static final long serialVersionUID = 1L;

    public static final MalformedBar MALFORMED_BAR = new MalformedBar();

    /** Malformed: empty string is rejected by validateLookup. */
    public static final String __NODE_TYPE_ID = "";
    /** Also malformed (empty array), but validation never reaches this check. */
    public static final org.jooq.Field<?>[] __NODE_KEY_COLUMNS = new org.jooq.Field<?>[0];

    @Override
    public Class<QuxRecord> getRecordType() {
        return QuxRecord.class;
    }

    private MalformedBar(Name alias, Table<QuxRecord> aliased) {
        super(alias, null, aliased, null, DSL.comment(""), TableOptions.table(), null);
    }

    public MalformedBar() {
        this(DSL.name("malformed_bar"), null);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public MalformedBar as(String alias) {
        return new MalformedBar(DSL.name(alias), this);
    }

    @Override
    public MalformedBar as(Name alias) {
        return new MalformedBar(alias, this);
    }

    @Override
    public MalformedBar rename(String name) {
        return new MalformedBar(DSL.name(name), null);
    }

    @Override
    public MalformedBar rename(Name name) {
        return new MalformedBar(name, null);
    }
}
