package fake.code.generated.mappers;

import fake.graphql.example.model.EndreInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.transforms.SomeTransform;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EndreInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EndreInput> endreInput, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var endreInputRecordList = new ArrayList<CustomerRecord>();

        if (endreInput != null) {
            for (var itEndreInput : endreInput) {
                if (itEndreInput == null) continue;
                var endreInputRecord = new CustomerRecord();
                endreInputRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    endreInputRecord.setId(itEndreInput.getId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    endreInputRecord.setFirstName(itEndreInput.getFirstName());
                }
                endreInputRecordList.add(endreInputRecord);
            }
        }
        endreInputRecordList = (ArrayList<CustomerRecord>) SomeTransform.someTransform(ctx, endreInputRecordList);

        return endreInputRecordList;
    }
}
