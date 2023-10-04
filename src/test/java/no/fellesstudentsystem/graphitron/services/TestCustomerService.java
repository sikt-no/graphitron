package no.fellesstudentsystem.graphitron.services;

import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.PaymentRecord;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Fake service for mutation tests. Does not need to return meaningful values as only the generated result is tested.
 */
public class TestCustomerService {
    private final DSLContext context;

    public TestCustomerService(DSLContext context) {
        this.context = context;
    }

    public String editCustomerID(String id) {
        return null;
    }

    public List<String> editCustomerIDList(List<String> id) {
        return null;
    }

    public CustomerRecord editCustomerRecord0(String id) {
        return null;
    }

    public List<CustomerRecord> editCustomerRecord1(List<String> id) {
        return null;
    }

    public List<CustomerRecord> editCustomerRecord2(List<String> id) {
        return null;
    }

    public String simple(String id) {
        return null;
    }

    public String editCustomerInput(CustomerRecord record) {
        return null;
    }

    public String editCustomerInput(CustomerRecord record, String s) {
        return null;
    } // It's a trap, shouldn't pick this one.

    public String editCustomer2Params(CustomerRecord record, String s) {
        return null;
    }

    public EditCustomerResponse editCustomerResponse(String id) {
        return null;
    }

    public EditCustomerResponse editCustomerInputAndResponse(CustomerRecord record) {
        return null;
    }

    public CustomerRecord editCustomerWithCustomer(String id) {
        return null;
    }

    public EditCustomerResponse editCustomerWithCustomerResponse(String id) {
        return null;
    }

    public List<String> editCustomerListSimple(List<String> ids) {
        return null;
    }

    public List<String> editCustomerListInput(List<CustomerRecord> records) {
        return null;
    }

    public List<String> editCustomerList2Params(List<CustomerRecord> records, List<String> s) {
        return null;
    }

    public List<EditCustomerResponse> editCustomerListResponse(List<String> ids) {
        return null;
    }

    public List<EditCustomerResponse> editCustomerListInputAndResponse(List<CustomerRecord> records) {
        return null;
    }

    public EditCustomerResponse editCustomerNested(CustomerRecord record0, CustomerRecord record1, CustomerRecord record2, CustomerRecord record3, List<CustomerRecord> recordList4, List<CustomerRecord> recordList5) {
        return new EditCustomerResponse();
    }

    public EditCustomerResponse editError(CustomerRecord record0, CustomerRecord record1) {
        return new EditCustomerResponse();
    }

    public EditCustomerResponse editErrorUnion1(String s) {
        return new EditCustomerResponse();
    }

    public EditCustomerResponse editErrorUnion2(CustomerRecord record0, CustomerRecord record1) {
        return new EditCustomerResponse();
    }

    public EditCustomerAddressResponse editCustomerAddress(CustomerRecord r) {
        return null;
    }

    public static class EditCustomerAddressResponse {
        public String getId() {
            return "";
        }
        public String getAddressId() {return "";}
    }

    public static class EditCustomerResponse {
        public String getId() {
            return "";
        }

        public String getFirstName() {
            return "";
        }

        public String getSecretEmail() {
            return "";
        }

        public EditCustomerResponse2 getEditResponse2() {
            return new EditCustomerResponse2();
        }

        public List<EditCustomerResponse3> getEditResponse3() {
            return List.of(new EditCustomerResponse3());
        }
    }

    public static class EditCustomerResponse2 {
        public String getId2() {
            return "";
        }

        public CustomerRecord getCustomer() {
            return new CustomerRecord();
        }
    }

    public static class EditCustomerResponse3 {
        public String getId3() {
            return "";
        }

        public CustomerRecord getCustomer3() {
            return new CustomerRecord();
        }

        public List<EditCustomerResponse4> getEdit4() {
            return List.of(new EditCustomerResponse4());
        }
    }

    public static class EditCustomerResponse4 {
        public String getId4() {
            return "";
        }

        public PaymentRecord getPayment4() {
            return new PaymentRecord();
        }
    }
}
