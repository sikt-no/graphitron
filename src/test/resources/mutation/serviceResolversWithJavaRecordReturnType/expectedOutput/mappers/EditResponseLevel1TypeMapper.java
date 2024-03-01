package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponseLevel1;
import fake.graphql.example.model.EditResponseLevel2B;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditResponseLevel1TypeMapper {
    public static List<EditResponseLevel1> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                        String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editResponseLevel1List = new ArrayList<EditResponseLevel1>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var editResponseLevel1 = new EditResponseLevel1();
                if (arguments.contains(pathHere + "id")) {
                    editResponseLevel1.setId(itTestCustomerRecord.getSomeID());
                }
                if (arguments.contains(pathHere + "idList")) {
                    editResponseLevel1.setIdList(itTestCustomerRecord.getSomeListID());
                }
                var record = itTestCustomerRecord.getRecord();
                if (record != null && arguments.contains(pathHere + "customer")) {
                    editResponseLevel1.setCustomer(transform.customerRecordToGraphType(record, pathHere + "customer"));
                }

                var recordList = itTestCustomerRecord.getRecordList();
                if (recordList != null && arguments.contains(pathHere + "customerList")) {
                    editResponseLevel1.setCustomerList(transform.customerRecordToGraphType(recordList, pathHere + "customerList"));
                }

                var edit2A = itTestCustomerRecord.getEdit2A();
                if (edit2A != null && arguments.contains(pathHere + "edit2A")) {
                    editResponseLevel1.setEdit2A(transform.editResponseLevel2AToGraphType(edit2A, pathHere + "edit2A"));
                }

                var testCustomerInnerRecord = itTestCustomerRecord.getTestCustomerInnerRecord();
                if (testCustomerInnerRecord != null && arguments.contains(pathHere + "edit2ADouble")) {
                    editResponseLevel1.setEdit2ADouble(transform.editResponseLevel2AToGraphType(testCustomerInnerRecord, pathHere + "edit2ADouble"));
                }

                var testCustomerInnerRecordList = itTestCustomerRecord.getTestCustomerInnerRecordList();
                if (testCustomerInnerRecordList != null && arguments.contains(pathHere + "edit2AList")) {
                    editResponseLevel1.setEdit2AList(transform.editResponseLevel2AToGraphType(testCustomerInnerRecordList, pathHere + "edit2AList"));
                }

                var recordList = itTestCustomerRecord.getRecordList();
                if (recordList != null && arguments.contains(pathHere + "edit2B")) {
                    var edit2B = new EditResponseLevel2B();
                    if (arguments.contains(pathHere + "edit2B/edit3")) {
                        edit2B.setEdit3(transform.editResponseLevel3BRecordToGraphType(recordList, pathHere + "edit2B/edit3"));
                    }
                    editResponseLevel1.setEdit2B(edit2B);
                }

                var recordList = itTestCustomerRecord.getRecordList();
                if (recordList != null && arguments.contains(pathHere + "edit2CList")) {
                    editResponseLevel1.setEdit2CList(transform.editResponseLevel2CToGraphType(recordList, pathHere + "edit2CList"));
                }

                editResponseLevel1List.add(editResponseLevel1);
            }
        }

        return editResponseLevel1List;
    }
}
