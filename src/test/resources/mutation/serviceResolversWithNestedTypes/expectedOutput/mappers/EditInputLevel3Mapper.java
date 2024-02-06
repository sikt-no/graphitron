package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel3;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel3Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel3> editInputLevel3, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel3RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel3 != null) {
            for (var itEditInputLevel3 : editInputLevel3) {
                if (itEditInputLevel3 == null) continue;
                var editInputLevel3Record = new CustomerRecord();
                editInputLevel3Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    editInputLevel3Record.setEmail(itEditInputLevel3.getEmail());
                }
                editInputLevel3RecordList.add(editInputLevel3Record);
            }
        }

        return editInputLevel3RecordList;
    }
}
