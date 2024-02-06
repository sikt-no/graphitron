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
                var edit2A = itEditInputLevel1.getEdit2A();
                if (edit2A != null && arguments.contains(pathHere + "edit2A")) {
                    testCustomerInputRecord.setEdit2A(transform.editInputLevel2AToJavaRecord(edit2A, pathHere + "edit2A"));
                }

                var edit2B = itEditInputLevel1.getEdit2B();
                if (edit2B != null) {
                    var edit3 = edit2B.getEdit3();
                    if (edit3 != null && arguments.contains(pathHere + "edit2B/edit3")) {
                        testCustomerInputRecord.setRecordList(transform.editInputLevel3BToJOOQRecord(edit3, pathHere + "edit2B/edit3"));
                    }

                }
                var edit2C = itEditInputLevel1.getEdit2C();
                if (edit2C != null) {
                    if (arguments.contains(pathHere + "edit2C/lastName")) {
                        testCustomerInputRecord.setName(edit2C.getLastName());
                    }
                }
                var edit2D = itEditInputLevel1.getEdit2D();
                var edit2E = itEditInputLevel1.getEdit2E();
                testCustomerInputRecordList.add(testCustomerInputRecord);
            }
        }

        return testCustomerInputRecordList;
    }
}
