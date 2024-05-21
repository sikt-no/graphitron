package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponseList;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditResponseListTypeMapper {
    public static List<EditResponseList> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                     String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponseListList = new ArrayList<EditResponseList>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var editResponseList = new EditResponseList();
                var testCustomerInnerRecord = itTestCustomerRecord.getTestCustomerInnerRecord();
                if (testCustomerInnerRecord != null && select.contains(pathHere + "testCustomerInnerRecord")) {
                    editResponseList.setTestCustomerInnerRecord(transform.editResultToGraphType(testCustomerInnerRecord, pathHere + "testCustomerInnerRecord"));
                }

                editResponseListList.add(editResponseList);
            }
        }

        return editResponseListList;
    }
}
