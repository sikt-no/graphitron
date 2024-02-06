package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel3;
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

public class EditInputLevel3Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel3> editInputLevel3, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel3RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel3 != null) {
            for (var itEditInputLevel3 : editInputLevel3) {
                if (itEditInputLevel3 == null) continue;
                var editInputLevel3Record = new CustomerRecord();
                editInputLevel3Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    editInputLevel3Record.setEmail(itEditInputLevel3.getEmail());
                }
                editInputLevel3RecordList.add(editInputLevel3Record);
            }
        }

        return editInputLevel3RecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> editInputLevel3RecordList,
                                             String path, Set<String> arguments, DataFetchingEnvironment env) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var validationErrors = new HashSet<GraphQLError>();

        for (int itEditInputLevel3RecordIndex = 0; itEditInputLevel3RecordIndex < editInputLevel3RecordList.size(); itEditInputLevel3RecordIndex++) {
            var itEditInputLevel3Record = editInputLevel3RecordList.get(itEditInputLevel3RecordIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "email")) {
                pathsForProperties.put("email", pathHere + itEditInputLevel3RecordIndex + "/email");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itEditInputLevel3Record, pathsForProperties, env));
        }

        return validationErrors;
    }
}
