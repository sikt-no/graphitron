package no.sikt.graphitron.codereferences.services;

import no.sikt.graphitron.codereferences.records.CustomerJavaRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CityRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Set;

/**
 * Fake service for mapper tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class MapperFetchService {
    public MapperFetchService(DSLContext context) {}

    public List<CustomerRecord> customerQuery() {
        return null;
    }

    public List<CustomerJavaRecord> customerJavaQuery() {
        return null;
    }

    public CustomerJavaRecord fetchCustomerDetails(Set<Integer> resolverKeys) {
        return null;
    }

    public List<CityRecord> cityQuery() {
        return null;
    }

}
