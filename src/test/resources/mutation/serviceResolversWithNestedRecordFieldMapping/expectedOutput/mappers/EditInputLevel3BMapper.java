package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel3B;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel3BMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel3B> editInputLevel3B,
                                                String path, Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel3BRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel3B != null) {
            for (var itEditInputLevel3B : editInputLevel3B) {
                if (itEditInputLevel3B == null) continue;
                var editInputLevel3BRecord = new CustomerRecord();
                editInputLevel3BRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    editInputLevel3BRecord.setLastName(itEditInputLevel3B.getLastName());
                }
                editInputLevel3BRecordList.add(editInputLevel3BRecord);
            }
        }

        return editInputLevel3BRecordList;
    }
}
