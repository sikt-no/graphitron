package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.QueryCustomerJavaRecord;

public class CustomerTypeMapper {
    public static List<Customer> toGraphType(List<QueryCustomerJavaRecord> queryCustomerJavaRecord,
                                             String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerList = new ArrayList<Customer>();

        if (queryCustomerJavaRecord != null) {
            for (var itQueryCustomerJavaRecord : queryCustomerJavaRecord) {
                if (itQueryCustomerJavaRecord == null) continue;
                var customer = new Customer();
                var address = itQueryCustomerJavaRecord.getAddress();
                if (address != null && select.contains(pathHere + "address1")) {
                    customer.setAddress1(transform.addressRecordToGraphType(address, pathHere + "address1"));
                }

                customerList.add(customer);
            }
        }

        return customerList;
    }
}
