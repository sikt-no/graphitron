package fake.code.generated.mappers;

import fake.graphql.example.model.EditInput2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInput2Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInput2> editInput2, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInput2RecordList = new ArrayList<CustomerRecord>();

        if (editInput2 != null) {
            for (var itEditInput2 : editInput2) {
                if (itEditInput2 == null) continue;
                var editInput2Record = new CustomerRecord();
                editInput2Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    editInput2Record.setEmail(itEditInput2.getEmail());
                }
                editInput2RecordList.add(editInput2Record);
            }
        }

        return editInput2RecordList;
    }
}
