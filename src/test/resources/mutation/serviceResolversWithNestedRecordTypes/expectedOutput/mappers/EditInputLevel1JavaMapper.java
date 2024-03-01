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
                var edit2A = itEditInputLevel1.getEdit2A();
                if (edit2A != null && arguments.contains(pathHere + "edit2A")) {
                    testCustomerRecord.setEdit2A(transform.editInputLevel2AToJavaRecord(edit2A, pathHere + "edit2A"));
                }

                var edit2ADouble = itEditInputLevel1.getEdit2ADouble();
                if (edit2ADouble != null && arguments.contains(pathHere + "edit2ADouble")) {
                    testCustomerRecord.setTestCustomerInnerRecord(transform.editInputLevel2AToJavaRecord(edit2ADouble, pathHere + "edit2ADouble"));
                }

                var edit2AList = itEditInputLevel1.getEdit2AList();
                if (edit2AList != null && arguments.contains(pathHere + "edit2AList")) {
                    testCustomerRecord.setTestCustomerInnerRecordList(transform.editInputLevel2AToJavaRecord(edit2AList, pathHere + "edit2AList"));
                }

                var edit2B = itEditInputLevel1.getEdit2B();
                if (edit2B != null && arguments.contains(pathHere + "edit2B")) {
                    var edit3 = edit2B.getEdit3();
                    if (edit3 != null && arguments.contains(pathHere + "edit2B/edit3")) {
                        testCustomerRecord.setRecordList(transform.editInputLevel3BToJOOQRecord(edit3, pathHere + "edit2B/edit3"));
                    }

                }
                var edit2CList = itEditInputLevel1.getEdit2CList();
                if (edit2CList != null && arguments.contains(pathHere + "edit2CList")) {
                    testCustomerRecord.setRecordList(transform.editInputLevel2CToJavaRecord(edit2CList, pathHere + "edit2CList"));
                }
                testCustomerRecordList.add(testCustomerRecord);
            }
        }

        return testCustomerRecordList;
    }
}
