import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.NodeIdInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.NodeIdInputJavaRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.resolvers.ArgumentPresence;

public class NodeIdInputJavaMapper {
    public static List<NodeIdInputJavaRecord> toJavaRecord(List<NodeIdInput> _mi_nodeIdInput,
                                                           NodeIdStrategy _iv_nodeIdStrategy, ArgumentPresence _iv_argPresence, String _iv_path, RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _mlo_nodeIdInputJavaRecord = new ArrayList<NodeIdInputJavaRecord>();

        if (_mi_nodeIdInput != null) {
            for (int _niit_nodeIdInput = 0; _niit_nodeIdInput < _mi_nodeIdInput.size(); _niit_nodeIdInput++) {
                var _nit_nodeIdInput = _mi_nodeIdInput.get(_niit_nodeIdInput);
                var _iv_args = _iv_argPresence.itemAt(_niit_nodeIdInput);
                if (_nit_nodeIdInput == null) continue;
                var _mo_nodeIdInputJavaRecord = new NodeIdInputJavaRecord();
                var _mi_customer = new CustomerRecord();
                var _mi_customerHasValue = false;
                if (_iv_args.hasField("customer")) {
                    var _iv_nodeIdValue = _nit_nodeIdInput.getCustomer();
                    if (_iv_nodeIdValue != null) {
                        _mi_customerHasValue = true;
                        _iv_nodeIdStrategy.setId(_mi_customer, _iv_nodeIdValue, "CustomerNode", Customer.CUSTOMER.CUSTOMER_ID);
                    }
                }

                _mo_nodeIdInputJavaRecord.setCustomer(_mi_customerHasValue ? _mi_customer : null);

                _mlo_nodeIdInputJavaRecord.add(_mo_nodeIdInputJavaRecord);
            }
        }

        return _mlo_nodeIdInputJavaRecord;
    }
}
