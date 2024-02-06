package fake.code.generated.mappers;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.model.EditInputLevel1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord;

public class EditInputLevel1JavaMapper {
    public static List<TestCustomerInputRecord> toJavaRecord(List<EditInputLevel1> editInputLevel1,
                                                       String path, Set<String> arguments, InputTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var testCustomerInputRecordList = new ArrayList<TestCustomerInputRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var testCustomerInputRecord = new TestCustomerInputRecord();
                var edit2 = itEditInputLevel1.getEdit2();
                if (edit2 != null && arguments.contains(pathHere + "edit2")) {
                    testCustomerInputRecord.setRecord(transform.editInputLevel2ToJOOQRecord(edit2, pathHere + "edit2"));
                }

                testCustomerInputRecordList.add(testCustomerInputRecord);
            }
        }

        return testCustomerInputRecordList;
    }
}

