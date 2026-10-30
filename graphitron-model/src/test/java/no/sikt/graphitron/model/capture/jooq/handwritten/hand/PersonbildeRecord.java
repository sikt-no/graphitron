package no.sikt.graphitron.model.capture.jooq.handwritten.hand;

import org.jooq.impl.TableRecordImpl;

/** {@link Personbilde}'s record, with the getters the default strategy writes for its upper-case columns. */
public class PersonbildeRecord extends TableRecordImpl<PersonbildeRecord> {

    private static final long serialVersionUID = 1L;

    public PersonbildeRecord() {
        super(Personbilde.PERSONBILDE);
    }

    public String getPersonlopenr() {
        return (String) get(0);
    }

    public String getBilde() {
        return (String) get(1);
    }
}
