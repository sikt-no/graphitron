package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyInputRecord;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.IDJavaRecord;

public class DummyInputRecordJavaMapper {
    public static List<IDJavaRecord> toJavaRecord(List<DummyInputRecord> _mi_dummyInputRecord,
                                                  String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _mlo_iDJavaRecord = new ArrayList<IDJavaRecord>();

        if (_mi_dummyInputRecord != null) {
            for (int _niit_dummyInputRecord = 0; _niit_dummyInputRecord < _mi_dummyInputRecord.size(); _niit_dummyInputRecord++) {
                var _nit_dummyInputRecord = _mi_dummyInputRecord.get(_niit_dummyInputRecord);
                var _iv_args = _iv_transform.getArgumentsForIndex(_iv_path, _niit_dummyInputRecord);
                if (_nit_dummyInputRecord == null) continue;
                var _mo_iDJavaRecord = new IDJavaRecord();
                if (_iv_args.contains(_iv_pathHere + "id")) {
                    _mo_iDJavaRecord.setId(_nit_dummyInputRecord.getId());
                }

                _mlo_iDJavaRecord.add(_mo_iDJavaRecord);
            }
        }

        return _mlo_iDJavaRecord;
    }
}
