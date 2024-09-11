package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class CustomerTableTypeMapper {
    public static List<CustomerTable> recordToGraphType(List<CustomerRecord> customerRecord,
                                                        String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerTableList = new ArrayList<CustomerTable>();

        if (customerRecord != null) {
            for (var itCustomerRecord : customerRecord) {
                if (itCustomerRecord == null) continue;
                var customerTable = new CustomerTable();
                if (select.contains(pathHere + "id")) {
                    customerTable.setId(itCustomerRecord.getId());
                }

                customerTableList.add(customerTable);
            }
        }

        return customerTableList;
    }
}
