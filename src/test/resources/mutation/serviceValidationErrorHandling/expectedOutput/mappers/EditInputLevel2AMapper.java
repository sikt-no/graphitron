package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel2A;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel2AMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2A> editInputLevel2A,
                                                String path, Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel2ARecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2A != null) {
            for (var itEditInputLevel2A : editInputLevel2A) {
                if (itEditInputLevel2A == null) continue;
                var editInputLevel2ARecord = new CustomerRecord();
                editInputLevel2ARecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "firstName")) {
                    editInputLevel2ARecord.setFirstName(itEditInputLevel2A.getFirstName());
                }
                editInputLevel2ARecordList.add(editInputLevel2ARecord);
            }
        }

        return editInputLevel2ARecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> editInputLevel2ARecordList,
                                             String path, Set<String> arguments, DataFetchingEnvironment env) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var validationErrors = new HashSet<GraphQLError>();

        for (int itEditInputLevel2ARecordIndex = 0; itEditInputLevel2ARecordIndex < editInputLevel2ARecordList.size(); itEditInputLevel2ARecordIndex++) {
            var itEditInputLevel2ARecord = editInputLevel2ARecordList.get(itEditInputLevel2ARecordIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "firstName")) {
                pathsForProperties.put("firstName", pathHere + itEditInputLevel2ARecordIndex + "/firstName");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itEditInputLevel2ARecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
