package no.sikt.graphitron.rewrite.platformidfixture.tables;

import no.sikt.graphitron.rewrite.platformidfixture.Public;
import no.sikt.graphitron.rewrite.platformidfixture.tables.records.QuxRecord;

import org.jooq.Name;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/**
 * Stock jOOQ-style table with no platform-id accessors. Used as the negative counterpart to
 * {@link Bar} in platform-id pipeline tests: the classifier's column lookup misses, the ID-scalar
 * platform-id fallback runs, and nothing matches — so the field lands on the unresolved branch.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Qux extends TableImpl<QuxRecord> {

    private static final long serialVersionUID = 1L;

    public static final Qux QUX = new Qux();

    @Override
    public Class<QuxRecord> getRecordType() {
        return QuxRecord.class;
    }

    public final TableField<QuxRecord, String> NAME =
        createField(DSL.name("name"), SQLDataType.VARCHAR, this, "");

    private Qux(Name alias, Table<QuxRecord> aliased) {
        super(alias, null, aliased, null, DSL.comment(""), TableOptions.table(), null);
    }

    public Qux() {
        this(DSL.name("qux"), null);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public Qux as(String alias) {
        return new Qux(DSL.name(alias), this);
    }

    @Override
    public Qux as(Name alias) {
        return new Qux(alias, this);
    }

    @Override
    public Qux rename(String name) {
        return new Qux(DSL.name(name), null);
    }

    @Override
    public Qux rename(Name name) {
        return new Qux(name, null);
    }
}
