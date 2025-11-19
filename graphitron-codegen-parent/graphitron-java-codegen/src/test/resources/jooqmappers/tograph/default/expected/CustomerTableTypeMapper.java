package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class CustomerTableTypeMapper {
    public static List<CustomerTable> recordToGraphType(List<CustomerRecord> _mi_customerRecord,
                                                        String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var _mlo_customerTable = new ArrayList<CustomerTable>();

        if (_mi_customerRecord != null) {
            for (var _nit_customerRecord : _mi_customerRecord) {
                if (_nit_customerRecord == null) continue;
                var _mo_customerTable = new CustomerTable();
                if (_iv_select.contains(_iv_pathHere + "id")) {
                    _mo_customerTable.setId(_nit_customerRecord.getId());
                }

                _mlo_customerTable.add(_mo_customerTable);
            }
        }

        return _mlo_customerTable;
    }
}
