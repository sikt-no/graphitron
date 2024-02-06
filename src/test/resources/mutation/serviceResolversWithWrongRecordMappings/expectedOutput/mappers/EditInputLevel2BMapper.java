package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel2B;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel2BMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2B> editInputLevel2B,
                                                String path, Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel2BRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2B != null) {
            for (var itEditInputLevel2B : editInputLevel2B) {
                if (itEditInputLevel2B == null) continue;
                var editInputLevel2BRecord = new CustomerRecord();
                editInputLevel2BRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    editInputLevel2BRecord.setLastName(itEditInputLevel2B.getLastName());
                }
                editInputLevel2BRecordList.add(editInputLevel2BRecord);
            }
        }

        return editInputLevel2BRecordList;
    }
}

