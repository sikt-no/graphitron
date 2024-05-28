package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerJava;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class CustomerJavaTypeMapper {
    public static List<CustomerJava> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerJavaList = new ArrayList<CustomerJava>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var customerJava = new CustomerJava();
                if (select.contains(pathHere + "id")) {
                    customerJava.setId(itTestCustomerRecord.getSomeID());
                }

                var record = itTestCustomerRecord.getRecord();
                if (record != null && select.contains(pathHere + "customer")) {
                    customerJava.setCustomer(transform.customerRecordToGraphType(record, pathHere + "customer"));
                }

                customerJavaList.add(customerJava);
            }
        }

        return customerJavaList;
    }
}

