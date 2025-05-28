package no.sikt.graphitron.codereferences.services;

import org.jooq.DSLContext;

/**
 * Fake service for mapper tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class ContextService {
    public ContextService(DSLContext context) {}

    public String query(String ctxField) {
        return null;
    }

    public String mutation(String ctxField) {
        return null;
    }

    public String query(int i, String ctxField) {
        return null;
    }

    public String mutation(int i, String ctxField) {
        return null;
    }
}
