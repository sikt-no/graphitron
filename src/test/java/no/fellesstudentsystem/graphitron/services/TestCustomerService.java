package no.fellesstudentsystem.graphitron.services;

import no.fellesstudentsystem.graphitron.records.EditCustomerAddressResponse;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse2;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
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

    public EditCustomerResponse1 editCustomerResponse(String id) {
        return null;
    }

    public EditCustomerResponse1 editCustomerInputAndResponse(CustomerRecord record) {
        return null;
    }

    public CustomerRecord editCustomerWithCustomer(String id) {
        return null;
    }

    public EditCustomerResponse1 editCustomerWithCustomerResponse(String id) {
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

    public List<EditCustomerResponse1> editCustomerListResponse(List<String> ids) {
        return null;
    }

    public List<EditCustomerResponse1> editCustomerListInputAndResponse(List<CustomerRecord> records) {
        return null;
    }

    public EditCustomerResponse1 editCustomerNested(CustomerRecord record0, CustomerRecord record1, CustomerRecord record2, CustomerRecord record3, List<CustomerRecord> recordList4, List<CustomerRecord> recordList5) {
        return new EditCustomerResponse1();
    }

    public EditCustomerResponse1 editError(CustomerRecord record0, CustomerRecord record1) {
        return new EditCustomerResponse1();
    }

    public List<EditCustomerResponse2> editErrorList(List<CustomerRecord> records) {
        return List.of(new EditCustomerResponse2());
    }

    public EditCustomerResponse1 editErrorUnion1(String s) {
        return new EditCustomerResponse1();
    }

    public EditCustomerResponse1 editErrorUnion2(CustomerRecord record0, CustomerRecord record1) {
        return new EditCustomerResponse1();
    }

    public EditCustomerAddressResponse editCustomerAddress(CustomerRecord r) {
        return null;
    }

    public CustomerRecord editCustomerWithRecordInputs(TestCustomerRecord record) {
        return null;
    }

    public CustomerRecord editCustomerWithRecordInputs(TestCustomerRecord record, String s) {
        return null;
    }

    public List<CustomerRecord> editCustomerWithRecordInputsList(TestCustomerRecord record, String s) {
        return null;
    }

    public CustomerRecord editCustomerQuery(List<String> ids, SelectionSet selectionSet) {
        return null;
    }

    public AddressRecord historicalAddresses(List<String> ids, SelectionSet selectionSet) {
        return null;
    }
}
