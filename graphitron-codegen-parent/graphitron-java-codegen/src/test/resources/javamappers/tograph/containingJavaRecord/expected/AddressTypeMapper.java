package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressTypeMapper {
    public static List<Address> toGraphType(List<MapperNestedJavaRecord> mapperNestedJavaRecord,
                                            String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var addressList = new ArrayList<Address>();

        if (mapperNestedJavaRecord != null) {
            for (var itMapperNestedJavaRecord : mapperNestedJavaRecord) {
                if (itMapperNestedJavaRecord == null) continue;
                var address = new Address();
                var dummyRecord = itMapperNestedJavaRecord.getDummyRecord();
                if (dummyRecord != null && _iv_select.contains(_iv_pathHere + "dummyRecord")) {
                    address.setDummyRecord(_iv_transform.dummyTypeRecordToGraphType(dummyRecord, _iv_pathHere + "dummyRecord"));
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
