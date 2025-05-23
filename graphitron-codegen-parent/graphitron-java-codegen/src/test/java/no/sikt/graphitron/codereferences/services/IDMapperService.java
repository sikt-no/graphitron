package no.sikt.graphitron.codereferences.services;

import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for mapper tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class IDMapperService {
    public IDMapperService(DSLContext context) {
    }

    public String query() {
        return null;
    }

    public List<String> queryListed() {
        return null;
    }
}
