package no.sikt.graphitron.codereferences.services;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.AddressRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

import java.util.List;

/**
 * Test service for validating parameter type mismatch detection.
 * Methods have unambiguous parameter types (no overloads sharing the same name).
 */
public class TypeMismatchService {
    public String check(CustomerRecord record) {
        return null;
    }

    public String checkNested(CustomerRecord customer, AddressRecord address) {
        return null;
    }

    public String checkNested(CustomerRecord customer, AddressRecord address, CustomerRecord customer2) {
        return null;
    }

    public String checkNestedWrongCount(CustomerRecord customer, AddressRecord address, CustomerRecord customer2) {
        return null;
    }

    public String checkString(String wrongType) {
        return null;
    }

    public String checkList(List<CustomerRecord> records) {
        return null;
    }

    public String checkIncorrect(List<String> wrongType) {
        return null;
    }

    public String checkAllInputFeatures(CustomerRecord input, String order, int first, String after, String ctx1, String ctx2) {
        return null;
    }

}
