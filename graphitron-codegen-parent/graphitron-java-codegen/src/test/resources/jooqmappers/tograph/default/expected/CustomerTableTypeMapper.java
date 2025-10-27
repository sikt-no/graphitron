package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class CustomerTableTypeMapper {
    public static List<CustomerTable> recordToGraphType(List<CustomerRecord> customerRecord,
                                                        String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var customerTableList = new ArrayList<CustomerTable>();

        if (customerRecord != null) {
            for (var itCustomerRecord : customerRecord) {
                if (itCustomerRecord == null) continue;
                var customerTable = new CustomerTable();
                if (_iv_select.contains(_iv_pathHere + "id")) {
                    customerTable.setId(itCustomerRecord.getId());
                }

                customerTableList.add(customerTable);
            }
        }

        return customerTableList;
    }
}
