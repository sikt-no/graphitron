package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class CustomerJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<Customer> customer, String path,
                                                    RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (customer != null) {
            for (var itCustomer : customer) {
                if (itCustomer == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "addressId")) {
                    customerRecord.setAddressId(itCustomer.getAddressId());
                }

                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
