package no.sikt.graphitron.model.capture.jooq.handwritten.hand;

import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/** A table whose record a renaming strategy generated: one getter is not where the default puts it. */
public class Renamed extends TableImpl<RenamedRecord> {

    private static final long serialVersionUID = 1L;

    public static final Renamed RENAMED = new Renamed();

    public final TableField<RenamedRecord, Integer> renamed_id =
        createField(DSL.name("renamed_id"), SQLDataType.INTEGER.nullable(false), this, "");

    public final TableField<RenamedRecord, String> label =
        createField(DSL.name("label"), SQLDataType.VARCHAR(40), this, "");

    private Renamed() {
        super(DSL.name("renamed"), Hand.HAND);
    }

    @Override
    public Class<RenamedRecord> getRecordType() {
        return RenamedRecord.class;
    }
}
