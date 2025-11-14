package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyTypeRecord;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;

public class DummyTypeRecordTypeMapper {
    public static List<DummyTypeRecord> toGraphType(List<IDJavaRecord> _mi_iDJavaRecord, String _iv_path,
                                                    RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var _mlo_dummyTypeRecord = new ArrayList<DummyTypeRecord>();

        if (_mi_iDJavaRecord != null) {
            for (var _nit_iDJavaRecord : _mi_iDJavaRecord) {
                if (_nit_iDJavaRecord == null) continue;
                var _mo_dummyTypeRecord = new DummyTypeRecord();
                if (_iv_select.contains(_iv_pathHere + "id")) {
                    _mo_dummyTypeRecord.setId(_nit_iDJavaRecord.getId());
                }

                _mlo_dummyTypeRecord.add(_mo_dummyTypeRecord);
            }
        }

        return _mlo_dummyTypeRecord;
    }
}
