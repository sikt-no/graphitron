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
    public static Set<GraphQLError> validate(List<CustomerRecord> _mi_customerRecord, String _iv_path,
                                             RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var _iv_env = _iv_transform.getEnv();
        var _iv_validationErrors = new HashSet<GraphQLError>();

        for (int _niit_customerRecord = 0; _niit_customerRecord < _mi_customerRecord.size(); _niit_customerRecord++) {
            var _nit_customerRecord = _mi_customerRecord.get(_niit_customerRecord);
            var _iv_pathsForProperties = new HashMap<String, String>();
            if (_iv_args.contains(_iv_pathHere + "id")) {
                _iv_pathsForProperties.put("id", _iv_pathHere + _niit_customerRecord + "/id");
            }
            _iv_validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(_nit_customerRecord, _iv_pathsForProperties, _iv_env));
        }

        return _iv_validationErrors;
    }
}
