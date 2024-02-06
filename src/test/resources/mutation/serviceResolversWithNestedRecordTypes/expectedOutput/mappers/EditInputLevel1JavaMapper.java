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

                var edit2ADouble = itEditInputLevel1.getEdit2ADouble();
                if (edit2ADouble != null && arguments.contains(pathHere + "edit2ADouble")) {
                    testCustomerInputRecord.setTestCustomerInnerInputRecord(transform.editInputLevel2AToJavaRecord(edit2ADouble, pathHere + "edit2ADouble"));
                }

                var edit2AList = itEditInputLevel1.getEdit2AList();
                if (edit2AList != null && arguments.contains(pathHere + "edit2AList")) {
                    testCustomerInputRecord.setTestCustomerInnerInputRecordList(transform.editInputLevel2AToJavaRecord(edit2AList, pathHere + "edit2AList"));
                }

                var edit2B = itEditInputLevel1.getEdit2B();
                if (edit2B != null) {
                    var edit3 = edit2B.getEdit3();
                    if (edit3 != null && arguments.contains(pathHere + "edit2B/edit3")) {
                        testCustomerInputRecord.setRecordList(transform.editInputLevel3BToJOOQRecord(edit3, pathHere + "edit2B/edit3"));
                    }

                }
                var edit2CList = itEditInputLevel1.getEdit2CList();
                if (edit2CList != null && arguments.contains(pathHere + "edit2CList")) {
                    testCustomerInputRecord.setRecordList(transform.editInputLevel2CToJavaRecord(edit2CList, pathHere + "edit2CList"));
                }
                testCustomerInputRecordList.add(testCustomerInputRecord);
            }
        }

        return testCustomerInputRecordList;
    }
}
