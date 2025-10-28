package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressTypeMapper {
    public static List<Address> toGraphType(List<MapperNestedJavaRecord> _mi_mapperNestedJavaRecord,
                                            String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var _mlo_address = new ArrayList<Address>();

        if (_mi_mapperNestedJavaRecord != null) {
            for (var _nit_mapperNestedJavaRecord : _mi_mapperNestedJavaRecord) {
                if (_nit_mapperNestedJavaRecord == null) continue;
                var _mo_address = new Address();
                var _mi_customer = _nit_mapperNestedJavaRecord.getCustomer();
                if (_mi_customer != null && _iv_select.contains(_iv_pathHere + "customer")) {
                    _mo_address.setCustomer(_iv_transform.customerTableRecordToGraphType(_mi_customer, _iv_pathHere + "customer"));
                }

                _mlo_address.add(_mo_address);
            }
        }

        return _mlo_address;
    }
}
