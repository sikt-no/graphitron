package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressJavaMapper {
    public static List<MapperNestedJavaRecord> toJavaRecord(List<Address> _mi_address, String _iv_path,
                                                            RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var _mlo_mapperNestedJavaRecord = new ArrayList<MapperNestedJavaRecord>();

        if (_mi_address != null) {
            for (var _nit_address : _mi_address) {
                if (_nit_address == null) continue;
                var _mo_mapperNestedJavaRecord = new MapperNestedJavaRecord();
                var _mi_dummyRecord = _nit_address.getDummyRecord();
                if (_mi_dummyRecord != null && _iv_args.contains(_iv_pathHere + "dummyRecord")) {
                    _mo_mapperNestedJavaRecord.setDummyRecord(_iv_transform.dummyInputRecordToJavaRecord(_mi_dummyRecord, _iv_pathHere + "dummyRecord"));
                }

                _mlo_mapperNestedJavaRecord.add(_mo_mapperNestedJavaRecord);
            }
        }

        return _mlo_mapperNestedJavaRecord;
    }
}
