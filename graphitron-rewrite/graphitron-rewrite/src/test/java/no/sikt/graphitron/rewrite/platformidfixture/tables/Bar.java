package no.sikt.graphitron.rewrite.platformidfixture.tables;

import no.sikt.graphitron.rewrite.platformidfixture.Public;
import no.sikt.graphitron.rewrite.platformidfixture.tables.records.BarRecord;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Schema;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/**
 * Hand-written jOOQ table that mimics the shape the custom {@code KjerneJooqGenerator} emits for
 * tables with a legacy composite platform key: two real key columns ({@code ID_1}, {@code ID_2})
 * plus the {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} metadata constants the rewrite's
 * {@link no.sikt.graphitron.rewrite.JooqCatalog#nodeIdMetadata} probe reflects on. Retains the
 * legacy {@code get*Id() -> SelectField<String>} instance methods and the record-level
 * {@code getId}/{@code setId}/{@code getPersonId}/{@code setPersonId} accessors — these continue
 * to drive the platform-id classification path until it is deleted in Step 5.
 *
 * <p>Used only by platform-id pipeline tests. {@link BarRecord} is the paired record class.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Bar extends TableImpl<BarRecord> {

    private static final long serialVersionUID = 1L;

    public static final Bar BAR = new Bar();

    /**
     * Node-identity metadata emitted by the custom {@code KjerneJooqGenerator}. The rewrite reads
     * these two constants via reflection to synthesize {@code NodeType} classification for
     * platform-id tables; see the plan at {@code docs/planning/plan-nodeid-directives.md}.
     */
    public static final String __NODE_TYPE_ID = "Bar";

    public static final org.jooq.Field<?>[] __NODE_KEY_COLUMNS = { BAR.ID_1, BAR.ID_2 };

    @Override
    public Class<BarRecord> getRecordType() {
        return BarRecord.class;
    }

    public final TableField<BarRecord, String> NAME =
        createField(DSL.name("name"), SQLDataType.VARCHAR, this, "");

    public final TableField<BarRecord, String> ID_1 =
        createField(DSL.name("id_1"), SQLDataType.VARCHAR, this, "");

    public final TableField<BarRecord, String> ID_2 =
        createField(DSL.name("id_2"), SQLDataType.VARCHAR, this, "");

    private Bar(Name alias, Table<BarRecord> aliased) {
        super(alias, null, aliased, null, DSL.comment(""), TableOptions.table(), null);
    }

    public Bar() {
        this(DSL.name("bar"), null);
    }

    public SelectField<String> getId() {
        return DSL.inline("", String.class);
    }

    public SelectField<String> getPersonId() {
        return DSL.inline("", String.class);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public Bar as(String alias) {
        return new Bar(DSL.name(alias), this);
    }

    @Override
    public Bar as(Name alias) {
        return new Bar(alias, this);
    }

    @Override
    public Bar rename(String name) {
        return new Bar(DSL.name(name), null);
    }

    @Override
    public Bar rename(Name name) {
        return new Bar(name, null);
    }
}
