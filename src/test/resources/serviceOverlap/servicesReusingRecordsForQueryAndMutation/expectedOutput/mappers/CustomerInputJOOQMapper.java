package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class CustomerInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<CustomerInput> customerInput, String path,
                                                    RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (customerInput != null) {
            for (var itCustomerInput : customerInput) {
                if (itCustomerInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itCustomerInput.getId());
                }

                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}

