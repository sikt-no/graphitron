package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel1;
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

public class EditInputLevel1Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel1> editInputLevel1, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel1RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var editInputLevel1Record = new CustomerRecord();
                editInputLevel1Record.attach(ctx.configuration());
                var editC1 = itEditInputLevel1.getEditC1();
                if (editC1 != null) {
                    if (arguments.contains(pathHere + "editC1/lastName")) {
                        editInputLevel1Record.setLastName(editC1.getLastName());
                    }
                }
                var editC2 = itEditInputLevel1.getEditC2();
                if (arguments.contains(pathHere + "id")) {
                    editInputLevel1Record.setId(itEditInputLevel1.getId());
                }
                editInputLevel1RecordList.add(editInputLevel1Record);
            }
        }

        return editInputLevel1RecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> editInputLevel1RecordList,
                                             String path, Set<String> arguments, DataFetchingEnvironment env) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var validationErrors = new HashSet<GraphQLError>();

        for (int itEditInputLevel1RecordIndex = 0; itEditInputLevel1RecordIndex < editInputLevel1RecordList.size(); itEditInputLevel1RecordIndex++) {
            var itEditInputLevel1Record = editInputLevel1RecordList.get(itEditInputLevel1RecordIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "editC1/lastName")) {
                pathsForProperties.put("lastName", pathHere + itEditInputLevel1RecordIndex + "/editC1/lastName");
            }
            if (arguments.contains(pathHere + "id")) {
                pathsForProperties.put("id", pathHere + itEditInputLevel1RecordIndex + "/id");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itEditInputLevel1Record, pathsForProperties, env));
        }

        return validationErrors;
    }
}
