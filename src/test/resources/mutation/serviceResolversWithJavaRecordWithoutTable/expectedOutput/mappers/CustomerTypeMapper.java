package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class CustomerTypeMapper {
    public static List<Customer> recordToGraphType(List<CustomerRecord> customerRecord, String path,
                                                   RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var customerList = new ArrayList<Customer>();

        if (customerRecord != null) {
            for (var itCustomerRecord : customerRecord) {
                if (itCustomerRecord == null) continue;
                var customer = new Customer();
                if (arguments.contains(pathHere + "id")) {
                    customer.setId(itCustomerRecord.getId());
                }

                if (arguments.contains(pathHere + "firstName")) {
                    customer.setFirstName(itCustomerRecord.getFirstName());
                }

                if (arguments.contains(pathHere + "lastName")) {
                    customer.setLastName(itCustomerRecord.getLastName());
                }

                if (arguments.contains(pathHere + "email")) {
                    customer.setEmail(itCustomerRecord.getEmail());
                }

                customerList.add(customer);
            }
        }

        return customerList;
    }
}
