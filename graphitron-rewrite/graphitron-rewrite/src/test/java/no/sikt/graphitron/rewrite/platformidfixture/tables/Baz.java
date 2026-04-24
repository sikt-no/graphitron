package no.sikt.graphitron.rewrite.platformidfixture.tables;

import no.sikt.graphitron.rewrite.platformidfixture.Public;
import no.sikt.graphitron.rewrite.platformidfixture.tables.records.BazRecord;

import org.jooq.ForeignKey;
import org.jooq.Name;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

import java.util.List;

/**
 * Synthetic jOOQ table used as the NodeType FK-target for NodeIdReferenceField pipeline tests.
 * Carries {@code __NODE_TYPE_ID = "Baz"} and {@code __NODE_KEY_COLUMNS = { BAZ.ID }} so the
 * rewrite classifies it as a {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}.
 * {@link Bar} holds an FK referencing {@code baz.id} via {@code bar.id_1}.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Baz extends TableImpl<BazRecord> {

    private static final long serialVersionUID = 1L;

    public static final Baz BAZ = new Baz();

    public static final String __NODE_TYPE_ID = "Baz";

    public static final org.jooq.Field<?>[] __NODE_KEY_COLUMNS = { BAZ.ID };

    @Override
    public Class<BazRecord> getRecordType() {
        return BazRecord.class;
    }

    public final TableField<BazRecord, String> ID =
        createField(DSL.name("id"), SQLDataType.VARCHAR, this, "");

    private Baz(Name alias, Table<BazRecord> aliased) {
        super(alias, null, aliased, null, DSL.comment(""), TableOptions.table(), null);
    }

    public Baz() {
        this(DSL.name("baz"), null);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public List<ForeignKey<BazRecord, ?>> getReferences() {
        return List.of();
    }

    @Override
    public Baz as(String alias) {
        return new Baz(DSL.name(alias), this);
    }

    @Override
    public Baz as(Name alias) {
        return new Baz(alias, this);
    }

    @Override
    public Baz rename(String name) {
        return new Baz(DSL.name(name), null);
    }

    @Override
    public Baz rename(Name name) {
        return new Baz(name, null);
    }
}
