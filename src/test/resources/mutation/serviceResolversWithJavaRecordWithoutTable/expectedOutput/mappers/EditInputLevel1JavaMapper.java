package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditInputLevel1JavaMapper {
    public static List<TestCustomerRecord> toJavaRecord(List<EditInputLevel1> editInputLevel1,
                                                       String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var testCustomerRecordList = new ArrayList<TestCustomerRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var testCustomerRecord = new TestCustomerRecord();
                var edit2 = itEditInputLevel1.getEdit2();
                if (edit2 != null && arguments.contains(pathHere + "edit2")) {
                    testCustomerRecord.setRecord(transform.editInputLevel2ToJOOQRecord(edit2, pathHere + "edit2"));
                }

                testCustomerRecordList.add(testCustomerRecord);
            }
        }

        return testCustomerRecordList;
    }
}

