package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import graphql.GraphQLError;
import java.lang.String;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;
import no.sikt.graphitron.validation.RecordValidator;

public class AddressJOOQMapper {
    public static Set<GraphQLError> validate(List<AddressRecord> addressRecordList, String path,
                                             RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var env = transform.getEnv();
        var validationErrors = new HashSet<GraphQLError>();

        for (int itAddressRecordListIndex = 0; itAddressRecordListIndex < addressRecordList.size(); itAddressRecordListIndex++) {
            var itAddressRecord = addressRecordList.get(itAddressRecordListIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "inner/postalCode")) {
                pathsForProperties.put("postalCode", pathHere + itAddressRecordListIndex + "/inner/postalCode");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itAddressRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
