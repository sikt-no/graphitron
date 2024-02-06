package fake.code.generated.mappers;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.model.EditInputLevel2C;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord;

public class EditInputLevel2CJavaMapper {
    public static List<TestCustomerInputRecord> toJavaRecord(List<EditInputLevel2C> editInputLevel2C,
                                                       String path, Set<String> arguments, InputTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var testCustomerInputRecordList = new ArrayList<TestCustomerInputRecord>();

        if (editInputLevel2C != null) {
            for (var itEditInputLevel2C : editInputLevel2C) {
                if (itEditInputLevel2C == null) continue;
                var testCustomerInputRecord = new TestCustomerInputRecord();
                var edit3 = itEditInputLevel2C.getEdit3();
                if (edit3 != null && arguments.contains(pathHere + "edit3")) {
                    testCustomerInputRecord.setRecord(transform.editInputLevel3BToJOOQRecord(edit3, pathHere + "edit3"));
                }

                testCustomerInputRecordList.add(testCustomerInputRecord);
            }
        }

        return testCustomerInputRecordList;
    }
}
