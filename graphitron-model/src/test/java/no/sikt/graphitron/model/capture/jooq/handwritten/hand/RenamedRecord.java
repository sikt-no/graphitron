package no.sikt.graphitron.model.capture.jooq.handwritten.hand;

import org.jooq.impl.TableRecordImpl;

/**
 * {@link Renamed}'s record: {@code renamed_id} keeps the default getter, {@code label} is read through
 * a name the default rule does not produce.
 */
public class RenamedRecord extends TableRecordImpl<RenamedRecord> {

    private static final long serialVersionUID = 1L;

    public RenamedRecord() {
        super(Renamed.RENAMED);
    }

    public Integer getRenamedId() {
        return (Integer) get(0);
    }

    public String fetchLabelText() {
        return (String) get(1);
    }
}
