package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;
import no.sikt.graphql.helpers.resolvers.ArgumentPresence;

public class AddressJavaMapper {
    public static List<MapperNestedJavaRecord> toJavaRecord(List<Address> _mi_address,
                                                            ArgumentPresence _iv_argPresence, String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _mlo_mapperNestedJavaRecord = new ArrayList<MapperNestedJavaRecord>();

        if (_mi_address != null) {
            for (int _niit_address = 0; _niit_address < _mi_address.size(); _niit_address++) {
                var _nit_address = _mi_address.get(_niit_address);
                var _iv_args = _iv_argPresence.itemAt(_niit_address);
                if (_nit_address == null) continue;
                var _mo_mapperNestedJavaRecord = new MapperNestedJavaRecord();
                var _mi_dummyRecord = _nit_address.getDummyRecord();
                if (_mi_dummyRecord != null && _iv_args.hasField("dummyRecord")) {
                    _mo_mapperNestedJavaRecord.setDummyRecord(_iv_transform.dummyInputRecordToJavaRecord(_mi_dummyRecord, _iv_args.child("dummyRecord"), _iv_path + "[" + _niit_address + "]/dummyRecord"));
                }

                _mlo_mapperNestedJavaRecord.add(_mo_mapperNestedJavaRecord);
            }
        }

        return _mlo_mapperNestedJavaRecord;
    }
}
