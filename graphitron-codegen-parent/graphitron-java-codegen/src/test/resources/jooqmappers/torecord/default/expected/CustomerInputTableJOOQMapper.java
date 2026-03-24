package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerInputTable;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphql.helpers.resolvers.ArgumentPresence;

public class CustomerInputTableJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<CustomerInputTable> _mi_customerInputTable,
                                                    ArgumentPresence _iv_argPresence, String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_ctx = _iv_transform.getCtx();
        var _mlo_customerRecord = new ArrayList<CustomerRecord>();

        if (_mi_customerInputTable != null) {
            for (int _niit_customerInputTable = 0; _niit_customerInputTable < _mi_customerInputTable.size(); _niit_customerInputTable++) {
                var _nit_customerInputTable = _mi_customerInputTable.get(_niit_customerInputTable);
                var _iv_args = _iv_argPresence.itemAt(_niit_customerInputTable);
                if (_nit_customerInputTable == null) continue;
                var _mo_customerRecord = new CustomerRecord();
                _mo_customerRecord.attach(_iv_ctx.configuration());
                if (_iv_args.hasField("id")) {
                    _mo_customerRecord.setId(_nit_customerInputTable.getId());
                }

                _mlo_customerRecord.add(_mo_customerRecord);
            }
        }

        return _mlo_customerRecord;
    }
}
