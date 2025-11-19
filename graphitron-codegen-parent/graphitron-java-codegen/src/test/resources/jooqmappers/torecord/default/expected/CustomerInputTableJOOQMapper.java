package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;

public class CustomerInputTableJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<CustomerInputTable> _mi_customerInputTable,
                                                    String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var _iv_ctx = _iv_transform.getCtx();
        var _mlo_customerRecord = new ArrayList<CustomerRecord>();

        if (_mi_customerInputTable != null) {
            for (var _nit_customerInputTable : _mi_customerInputTable) {
                if (_nit_customerInputTable == null) continue;
                var _mo_customerRecord = new CustomerRecord();
                _mo_customerRecord.attach(_iv_ctx.configuration());
                if (_iv_args.contains(_iv_pathHere + "id")) {
                    _mo_customerRecord.setId(_nit_customerInputTable.getId());
                }

                _mlo_customerRecord.add(_mo_customerRecord);
            }
        }

        return _mlo_customerRecord;
    }
}
