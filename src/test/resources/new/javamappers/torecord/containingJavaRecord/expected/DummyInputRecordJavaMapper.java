package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyInputRecord;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.IDJavaRecord;

public class DummyInputRecordJavaMapper {
    public static List<IDJavaRecord> toJavaRecord(List<DummyInputRecord> dummyInputRecord,
                                                  String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var iDJavaRecordList = new ArrayList<IDJavaRecord>();

        if (dummyInputRecord != null) {
            for (var itDummyInputRecord : dummyInputRecord) {
                if (itDummyInputRecord == null) continue;
                var iDJavaRecord = new IDJavaRecord();
                if (arguments.contains(pathHere + "id")) {
                    iDJavaRecord.setId(itDummyInputRecord.getId());
                }

                iDJavaRecordList.add(iDJavaRecord);
            }
        }

        return iDJavaRecordList;
    }
}
