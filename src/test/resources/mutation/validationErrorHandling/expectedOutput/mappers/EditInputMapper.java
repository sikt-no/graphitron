package fake.code.generated.mappers;

import fake.graphql.example.model.EditInput;
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

public class EditInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInput> editInput, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputRecordList = new ArrayList<CustomerRecord>();

        if (editInput != null) {
            for (var itEditInput : editInput) {
                if (itEditInput == null) continue;
                var editInputRecord = new CustomerRecord();
                editInputRecord.attach(ctx.configuration());
                var name = itEditInput.getName();
                if (name != null) {
                    if (arguments.contains(pathHere + "name/firstName")) {
                        editInputRecord.setFirstName(name.getFirstName());
                    }
                    if (arguments.contains(pathHere + "name/surname")) {
                        editInputRecord.setLastName(name.getSurname());
                    }
                }
                if (arguments.contains(pathHere + "id")) {
                    editInputRecord.setId(itEditInput.getId());
                }
                editInputRecordList.add(editInputRecord);
            }
        }

        return editInputRecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> editInputRecordList, String path,
                                             Set<String> arguments, DataFetchingEnvironment env) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var validationErrors = new HashSet<GraphQLError>();

        for (int itEditInputRecordIndex = 0; itEditInputRecordIndex < editInputRecordList.size(); itEditInputRecordIndex++) {
            var itEditInputRecord = editInputRecordList.get(itEditInputRecordIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "name/firstName")) {
                pathsForProperties.put("firstName", pathHere + itEditInputRecordIndex + "/name/firstName");
            }
            if (arguments.contains(pathHere + "name/surname")) {
                pathsForProperties.put("lastName", pathHere + itEditInputRecordIndex + "/name/surname");
            }
            if (arguments.contains(pathHere + "id")) {
                pathsForProperties.put("id", pathHere + itEditInputRecordIndex + "/id");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itEditInputRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
