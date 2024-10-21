package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class CustomerInputTableJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<CustomerInputTable> customerInputTable,
                                                    String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (customerInputTable != null) {
            for (var itCustomerInputTable : customerInputTable) {
                if (itCustomerInputTable == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itCustomerInputTable.getId());
                }

                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
