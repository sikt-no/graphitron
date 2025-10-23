package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class CustomerInputTableJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<CustomerInputTable> customerInputTable,
                                                    String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var _iv_ctx = _iv_transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (customerInputTable != null) {
            for (var itCustomerInputTable : customerInputTable) {
                if (itCustomerInputTable == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(_iv_ctx.configuration());
                if (_iv_args.contains(_iv_pathHere + "id")) {
                    customerRecord.setId(itCustomerInputTable.getId());
                }

                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
