package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;
import org.jooq.Row1;

import java.util.Map;
import java.util.Set;

/**
 * R49 ServiceRecordField Phase A fixture — child {@code @service} method with a non-table
 * scalar return type.
 *
 * <p>The signature follows the {@code Set<RowN>} / {@code Map<RowN, V>} mapped-batch contract:
 * the framework hands the method the set of parent {@code FILM_ID} keys and expects a map
 * back. Phase A does not invoke the body; the generated rows-method itself is a stub that
 * throws {@link UnsupportedOperationException} until R32 (service-rows-method-body) lands.
 */
public final class FilmService {

    private FilmService() {}

    /**
     * Phase A stub — signature only. The body throws to mirror the generated rows-method's
     * stub body. Phase B replaces this with a real per-tenant query that returns
     * {@code title.toUpperCase()} for each requested film id.
     */
    public static Map<Row1<Integer>, String> titleUppercase(Set<Row1<Integer>> filmIds, DSLContext dsl) {
        throw new UnsupportedOperationException();
    }
}
