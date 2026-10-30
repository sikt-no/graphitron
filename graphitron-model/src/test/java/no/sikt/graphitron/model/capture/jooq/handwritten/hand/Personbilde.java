package no.sikt.graphitron.model.capture.jooq.handwritten.hand;

import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/** An Oracle-style table whose names are upper-case, as a default strategy generates it. */
public class Personbilde extends TableImpl<PersonbildeRecord> {

    private static final long serialVersionUID = 1L;

    public static final Personbilde PERSONBILDE = new Personbilde();

    public final TableField<PersonbildeRecord, String> PERSONLOPENR =
        createField(DSL.name("PERSONLOPENR"), SQLDataType.VARCHAR(11).nullable(false), this, "");

    public final TableField<PersonbildeRecord, String> BILDE =
        createField(DSL.name("BILDE"), SQLDataType.VARCHAR(4000), this, "");

    private Personbilde() {
        super(DSL.name("PERSONBILDE"), Hand.HAND);
    }

    @Override
    public Class<PersonbildeRecord> getRecordType() {
        return PersonbildeRecord.class;
    }
}
