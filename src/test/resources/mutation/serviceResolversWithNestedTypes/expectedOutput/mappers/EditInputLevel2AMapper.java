package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel2A;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel2AMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2A> editInputLevel2A,
                                                String path, Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel2ARecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2A != null) {
            for (var itEditInputLevel2A : editInputLevel2A) {
                if (itEditInputLevel2A == null) continue;
                var editInputLevel2ARecord = new CustomerRecord();
                editInputLevel2ARecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "firstName")) {
                    editInputLevel2ARecord.setFirstName(itEditInputLevel2A.getFirstName());
                }
                editInputLevel2ARecordList.add(editInputLevel2ARecord);
            }
        }

        return editInputLevel2ARecordList;
    }
}
