package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerOuterWrapped;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class CustomerOuterWrappedTypeMapper {
    public static List<CustomerOuterWrapped> recordToGraphType(List<CustomerRecord> customerRecord,
                                                               String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerOuterWrappedList = new ArrayList<CustomerOuterWrapped>();

        if (customerRecord != null) {
            for (var itCustomerRecord : customerRecord) {
                if (itCustomerRecord == null) continue;
                var customerOuterWrapped = new CustomerOuterWrapped();
                if (select.contains(pathHere + "id")) {
                    customerOuterWrapped.setId(itCustomerRecord.getId());
                }

                customerOuterWrappedList.add(customerOuterWrapped);
            }
        }

        return customerOuterWrappedList;
    }
}
