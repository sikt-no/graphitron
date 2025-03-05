package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyTypeRecord;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;

public class DummyTypeRecordTypeMapper {
    public static List<DummyTypeRecord> toGraphType(List<IDJavaRecord> iDJavaRecord, String path,
                                                    RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var dummyTypeRecordList = new ArrayList<DummyTypeRecord>();

        if (iDJavaRecord != null) {
            for (var itIDJavaRecord : iDJavaRecord) {
                if (itIDJavaRecord == null) continue;
                var dummyTypeRecord = new DummyTypeRecord();
                if (select.contains(pathHere + "id")) {
                    dummyTypeRecord.setId(itIDJavaRecord.getId());
                }

                dummyTypeRecordList.add(dummyTypeRecord);
            }
        }

        return dummyTypeRecordList;
    }
}
