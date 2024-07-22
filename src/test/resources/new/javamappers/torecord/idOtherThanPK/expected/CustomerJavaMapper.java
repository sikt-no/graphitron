package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.CustomerJavaRecord;

public class CustomerJavaMapper {
    public static List<CustomerJavaRecord> toJavaRecord(List<Customer> customer, String path,
                                                        RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var customerJavaRecordList = new ArrayList<CustomerJavaRecord>();

        if (customer != null) {
            for (var itCustomer : customer) {
                if (itCustomer == null) continue;
                var customerJavaRecord = new CustomerJavaRecord();
                if (arguments.contains(pathHere + "addressId")) {
                    customerJavaRecord.setAddressId(itCustomer.getAddressId());
                }

                customerJavaRecordList.add(customerJavaRecord);
            }
        }

        return customerJavaRecordList;
    }
}
