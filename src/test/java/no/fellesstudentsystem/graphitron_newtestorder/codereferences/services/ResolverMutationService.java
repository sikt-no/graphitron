package no.fellesstudentsystem.graphitron_newtestorder.codereferences.services;

import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

import java.util.List;

/**
 * Fake service for resolver tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class ResolverMutationService {
    public String mutation() {
        return null;
    }
    public String mutation(String s) {
        return null;
    }
    public String mutation(String s0, String s1) {
        return null;
    }
    public String mutation(CustomerRecord c) {
        return null;
    }
    public String mutation(DummyRecord d) {
        return null;
    }
    public String mutation(CustomerRecord c0, CustomerRecord c1) {
        return null;
    }
    public String mutation(CustomerRecord c0, CustomerRecord c1, CustomerRecord c2) {
        return null;
    }
    public String mutation(CustomerRecord c0, List<CustomerRecord> c1) {
        return null;
    }
    public String mutation(List<CustomerRecord> c0, List<CustomerRecord> c1) {
        return null;
    }
    public String mutation(List<CustomerRecord> s) {
        return null;
    }
    public List<String> mutationList() {
        return null;
    }
    public CustomerRecord mutationCustomer() {
        return null;
    }
    public List<CustomerRecord> mutationCustomerList() {
        return null;
    }
    public DummyRecord mutationDummy() {
        return null;
    }
}
