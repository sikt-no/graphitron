package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel2Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2> editInputLevel2, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel2RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2 != null) {
            for (var itEditInputLevel2 : editInputLevel2) {
                if (itEditInputLevel2 == null) continue;
                var editInputLevel2Record = new CustomerRecord();
                editInputLevel2Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    editInputLevel2Record.setLastName(itEditInputLevel2.getLastName());
                }
                editInputLevel2RecordList.add(editInputLevel2Record);
            }
        }

        return editInputLevel2RecordList;
    }
}
