package no.fellesstudentsystem.graphitron.services;

import no.fellesstudentsystem.graphitron.records.TestIDRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CityRecord;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Map;

/**
 * Fake service for service tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TestFetchCityService {
    private final DSLContext context;

    public TestFetchCityService(DSLContext context) {
        this.context = context;
    }

    public List<CityRecord> city(List<String> ids) {
        return null;
    }

    public Map<String, List<CityRecord>> cityPaginated(List<String> ids, int pageSize, String after) {
        return Map.of();
    }

    public int countCityPaginated(List<String> ids) {
        return 0;
    }

    public List<TestIDRecord> recordCity(List<String> ids) {
        return null;
    }

    public Map<String, List<TestIDRecord>> recordCityPaginated(List<String> ids, int pageSize, String after) {
        return Map.of();
    }

    public int countRecordCityPaginated(List<String> ids) {
        return 0;
    }
}
