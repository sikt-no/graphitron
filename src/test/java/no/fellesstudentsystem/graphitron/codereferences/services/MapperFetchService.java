package no.fellesstudentsystem.graphitron.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for mapper tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class MapperFetchService {
    public MapperFetchService(DSLContext context) {}

    public List<CustomerRecord> customerQuery() {
        return null;
    }
}
