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
                if (select.contains(pathHere + "id")) {
                    customer.setId(itQueryCustomerJavaRecord.getSomeID());
                }

                if (select.contains(pathHere + "otherID")) {
                    customer.setOtherID(itQueryCustomerJavaRecord.getOtherID());
                }

                customerList.add(customer);
            }
        }

        return customerList;
    }
}
