package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EndreInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.transforms.SomeTransform;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EndreInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EndreInput> endreInput, String path,
                                                    RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (endreInput != null) {
            for (var itEndreInput : endreInput) {
                if (itEndreInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itEndreInput.getId());
                }

                if (arguments.contains(pathHere + "firstName")) {
                    customerRecord.setFirstName(itEndreInput.getFirstName());
                }

                customerRecordList.add(customerRecord);
            }
        }
        customerRecordList = (ArrayList<CustomerRecord>) SomeTransform.someTransform(ctx, customerRecordList);

        return customerRecordList;
    }
}
