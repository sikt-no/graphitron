package no.fellesstudentsystem.graphitron_newtestorder.codereferences.services;

import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fake service for resolver tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class ResolverFetchService {
    public ResolverFetchService(DSLContext context) {}

    public CustomerRecord query(String id) {
        return null;
    }

    public CustomerRecord query(DummyRecord record) {
        return null;
    }

    public CustomerRecord query(CustomerRecord record) {
        return null;
    }

    public CustomerRecord query(Set<String> ids, String id) {
        return null;
    }

    public CustomerRecord query(Set<String> ids, CustomerRecord record) {
        return null;
    }

    public List<Pair<String, CustomerRecord>> queryList(int pageSize, String after) {
        return List.of();
    }

    public Integer countQueryList() {
        return 0;
    }

    public Map<String, List<Pair<String, CustomerRecord>>> queryMap(Set<String> ids, int pageSize, String after) {
        return Map.of();
    }

    public Integer countQueryMap(Set<String> ids) {
        return 0;
    }

    public List<CustomerRecord> queryList(CustomerRecord record, int pageSize, String after) {
        return List.of();
    }

    public Integer countQueryList(CustomerRecord record) {
        return 0;
    }

    public Map<String, CustomerRecord> queryMap(Set<String> ids, CustomerRecord record, int pageSize, String after) {
        return Map.of();
    }

    public Integer countQueryMap(Set<String> ids, CustomerRecord record) {
        return 0;
    }

    public DummyRecord queryJavaRecord() {
        return null;
    }

    public DummyRecord queryJavaRecord(Set<String> id) {
        return null;
    }
}
