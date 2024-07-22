package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.CustomerJavaRecord;

public class CustomerTypeMapper {
    public static List<Customer> toGraphType(List<CustomerJavaRecord> customerJavaRecord,
                                             String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerList = new ArrayList<Customer>();

        if (customerJavaRecord != null) {
            for (var itCustomerJavaRecord : customerJavaRecord) {
                if (itCustomerJavaRecord == null) continue;
                var customer = new Customer();
                if (select.contains(pathHere + "id")) {
                    customer.setId(itCustomerJavaRecord.getSomeID());
                }

                if (select.contains(pathHere + "otherID")) {
                    customer.setOtherID(itCustomerJavaRecord.getOtherID());
                }

                customerList.add(customer);
            }
        }

        return customerList;
    }
}
