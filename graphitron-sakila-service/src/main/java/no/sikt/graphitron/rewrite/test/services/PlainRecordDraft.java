package no.sikt.graphitron.rewrite.test.services;

/**
 * A jOOQ record of no table that nothing can construct, being abstract, for the case where an input
 * object is built into such a record: {@link FilmDraftService#countPlainRecord} takes it.
 */
public abstract class PlainRecordDraft implements org.jooq.Record {

    private static final long serialVersionUID = 1L;
}
