package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyInputRecord;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;

public class DummyInputRecordJavaMapper {
    public static List<IDJavaRecord> toJavaRecord(List<DummyInputRecord> dummyInputRecord,
                                                  String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var iDJavaRecordList = new ArrayList<IDJavaRecord>();

        if (dummyInputRecord != null) {
            for (var itDummyInputRecord : dummyInputRecord) {
                if (itDummyInputRecord == null) continue;
                var iDJavaRecord = new IDJavaRecord();
                if (_iv_args.contains(_iv_pathHere + "id")) {
                    iDJavaRecord.setId(itDummyInputRecord.getId());
                }

                iDJavaRecordList.add(iDJavaRecord);
            }
        }

        return iDJavaRecordList;
    }
}
