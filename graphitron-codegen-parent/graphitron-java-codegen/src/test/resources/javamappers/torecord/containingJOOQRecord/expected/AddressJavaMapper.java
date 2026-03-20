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
        var _mlo_mapperNestedJavaRecord = new ArrayList<MapperNestedJavaRecord>();

        if (_mi_address != null) {
            for (int _niit_address = 0; _niit_address < _mi_address.size(); _niit_address++) {
                var _nit_address = _mi_address.get(_niit_address);
                var _iv_args = _iv_transform.getArgumentsForIndex(_iv_path, _niit_address);
                if (_nit_address == null) continue;
                var _mo_mapperNestedJavaRecord = new MapperNestedJavaRecord();
                var _mi_customer = _nit_address.getCustomer();
                if (_mi_customer != null && _iv_args.contains(_iv_pathHere + "customer")) {
                    _mo_mapperNestedJavaRecord.setCustomer(_iv_transform.customerInputTableToJOOQRecord(_mi_customer, _iv_path + "[" + _niit_address + "]/customer"));
                }

                _mlo_mapperNestedJavaRecord.add(_mo_mapperNestedJavaRecord);
            }
        }

        return _mlo_mapperNestedJavaRecord;
    }
}
