package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel4;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel4JOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel4> editInputLevel4, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel4 != null) {
            for (var itEditInputLevel4 : editInputLevel4) {
                if (itEditInputLevel4 == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    customerRecord.setLastName(itEditInputLevel4.getLastName());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
