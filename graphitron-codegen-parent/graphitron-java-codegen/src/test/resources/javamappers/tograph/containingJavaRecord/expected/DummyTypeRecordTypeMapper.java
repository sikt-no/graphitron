package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyTypeRecord;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;

public class DummyTypeRecordTypeMapper {
    public static List<DummyTypeRecord> toGraphType(List<IDJavaRecord> iDJavaRecord, String _iv_path,
                                                    RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var dummyTypeRecordList = new ArrayList<DummyTypeRecord>();

        if (iDJavaRecord != null) {
            for (var itIDJavaRecord : iDJavaRecord) {
                if (itIDJavaRecord == null) continue;
                var dummyTypeRecord = new DummyTypeRecord();
                if (_iv_select.contains(_iv_pathHere + "id")) {
                    dummyTypeRecord.setId(itIDJavaRecord.getId());
                }

                dummyTypeRecordList.add(dummyTypeRecord);
            }
        }

        return dummyTypeRecordList;
    }
}
