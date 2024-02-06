package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel1Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel1> editInputLevel1, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel1RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var editInputLevel1Record = new CustomerRecord();
                editInputLevel1Record.attach(ctx.configuration());
                var editC1 = itEditInputLevel1.getEditC1();
                if (editC1 != null) {
                    if (arguments.contains(pathHere + "editC1/lastName")) {
                        editInputLevel1Record.setLastName(editC1.getLastName());
                    }
                }
                var editC2 = itEditInputLevel1.getEditC2();
                if (arguments.contains(pathHere + "id")) {
                    editInputLevel1Record.setId(itEditInputLevel1.getId());
                }
                editInputLevel1RecordList.add(editInputLevel1Record);
            }
        }

        return editInputLevel1RecordList;
    }
}
