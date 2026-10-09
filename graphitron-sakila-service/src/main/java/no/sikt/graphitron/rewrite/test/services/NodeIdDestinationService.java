package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Two consumers of one decoded film id, which decide where it lands: {@link #modify} takes the
 * generated film record, so the id is decoded into that record; {@link #find} takes the key
 * column's own Java type, so the id is decoded into the single key column.
 */
public final class NodeIdDestinationService {

    private NodeIdDestinationService() {}

    public static String modify(FilmRecord in) {
        return in == null ? null : String.valueOf(in.getFilmId());
    }

    public static String find(Integer id) {
        return id == null ? null : String.valueOf(id);
    }
}
