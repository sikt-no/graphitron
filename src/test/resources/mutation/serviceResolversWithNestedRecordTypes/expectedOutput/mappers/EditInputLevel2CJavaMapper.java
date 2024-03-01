package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel2C;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditInputLevel2CJavaMapper {
    public static List<TestCustomerRecord> toJavaRecord(List<EditInputLevel2C> editInputLevel2C,
                                                       String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var testCustomerRecordList = new ArrayList<TestCustomerRecord>();

        if (editInputLevel2C != null) {
            for (var itEditInputLevel2C : editInputLevel2C) {
                if (itEditInputLevel2C == null) continue;
                var testCustomerRecord = new TestCustomerRecord();
                var edit3 = itEditInputLevel2C.getEdit3();
                if (edit3 != null && arguments.contains(pathHere + "edit3")) {
                    testCustomerRecord.setRecord(transform.editInputLevel3BToJOOQRecord(edit3, pathHere + "edit3"));
                }

                testCustomerRecordList.add(testCustomerRecord);
            }
        }

        return testCustomerRecordList;
    }
}
