package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel2B;
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

public class EditInputLevel2BMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2B> editInputLevel2B,
                                                String path, Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel2BRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2B != null) {
            for (var itEditInputLevel2B : editInputLevel2B) {
                if (itEditInputLevel2B == null) continue;
                var editInputLevel2BRecord = new CustomerRecord();
                editInputLevel2BRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "firstName")) {
                    editInputLevel2BRecord.setFirstName(itEditInputLevel2B.getFirstName());
                }
                editInputLevel2BRecordList.add(editInputLevel2BRecord);
            }
        }

        return editInputLevel2BRecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> editInputLevel2BRecordList,
                                             String path, Set<String> arguments, DataFetchingEnvironment env) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var validationErrors = new HashSet<GraphQLError>();

        for (int itEditInputLevel2BRecordIndex = 0; itEditInputLevel2BRecordIndex < editInputLevel2BRecordList.size(); itEditInputLevel2BRecordIndex++) {
            var itEditInputLevel2BRecord = editInputLevel2BRecordList.get(itEditInputLevel2BRecordIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "firstName")) {
                pathsForProperties.put("firstName", pathHere + itEditInputLevel2BRecordIndex + "/firstName");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itEditInputLevel2BRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
