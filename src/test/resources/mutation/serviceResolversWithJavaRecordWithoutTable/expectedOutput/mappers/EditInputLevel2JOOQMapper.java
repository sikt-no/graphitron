package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel2JOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2> editInputLevel2, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2 != null) {
            for (var itEditInputLevel2 : editInputLevel2) {
                if (itEditInputLevel2 == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    customerRecord.setLastName(itEditInputLevel2.getLastName());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
