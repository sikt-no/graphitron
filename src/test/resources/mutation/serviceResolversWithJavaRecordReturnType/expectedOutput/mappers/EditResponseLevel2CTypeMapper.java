package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponseLevel2C;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditResponseLevel2CTypeMapper {
    public static List<EditResponseLevel2C> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                        String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponseLevel2CList = new ArrayList<EditResponseLevel2C>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var editResponseLevel2C = new EditResponseLevel2C();
                var record = itTestCustomerRecord.getRecord();
                if (record != null && select.contains(pathHere + "edit3")) {
                    editResponseLevel2C.setEdit3(transform.editResponseLevel3BRecordToGraphType(record, pathHere + "edit3"));
                }

                editResponseLevel2CList.add(editResponseLevel2C);
            }
        }

        return editResponseLevel2CList;
    }
}

