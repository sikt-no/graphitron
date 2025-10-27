package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import graphql.GraphQLError;
import java.lang.String;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphitron.validation.RecordValidator;

public class CustomerInputTableJOOQMapper {
    public static Set<GraphQLError> validate(List<CustomerRecord> customerRecordList, String _iv_path,
                                             RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var _iv_env = _iv_transform.getEnv();
        var _iv_validationErrors = new HashSet<GraphQLError>();

        for (int itCustomerRecordListIndex = 0; itCustomerRecordListIndex < customerRecordList.size(); itCustomerRecordListIndex++) {
            var itCustomerRecord = customerRecordList.get(itCustomerRecordListIndex);
            var _iv_pathsForProperties = new HashMap<String, String>();
            if (_iv_args.contains(_iv_pathHere + "id")) {
                _iv_pathsForProperties.put("id", _iv_pathHere + itCustomerRecordListIndex + "/id");
            }
            _iv_validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itCustomerRecord, _iv_pathsForProperties, _iv_env));
        }

        return _iv_validationErrors;
    }
}
