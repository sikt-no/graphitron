package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * A plain jOOQ {@code Record} backing class: assignable to {@link org.jooq.Record} but not to
 * {@code org.jooq.TableRecord}, the shape that classifies an SDL type as
 * {@code GraphitronType.JooqRecordType} (result side) or {@code JooqRecordInputType} (input
 * side). Abstract because classification reads only the reflected signature (assignability and
 * record-ness); nothing instantiates it, so the wide {@code Record} surface needs no
 * implementations.
 */
public abstract class PlainJooqRecord implements org.jooq.Record {
    private static final long serialVersionUID = 1L;
}
