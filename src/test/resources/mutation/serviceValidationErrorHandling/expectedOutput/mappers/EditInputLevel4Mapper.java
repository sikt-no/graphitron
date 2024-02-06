package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel4;
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

public class EditInputLevel4Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel4> editInputLevel4, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel4RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel4 != null) {
            for (var itEditInputLevel4 : editInputLevel4) {
                if (itEditInputLevel4 == null) continue;
                var editInputLevel4Record = new CustomerRecord();
                editInputLevel4Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    editInputLevel4Record.setLastName(itEditInputLevel4.getLastName());
                }
                editInputLevel4RecordList.add(editInputLevel4Record);
            }
        }

        return editInputLevel4RecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> editInputLevel4RecordList,
                                             String path, Set<String> arguments, DataFetchingEnvironment env) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var validationErrors = new HashSet<GraphQLError>();

        for (int itEditInputLevel4RecordIndex = 0; itEditInputLevel4RecordIndex < editInputLevel4RecordList.size(); itEditInputLevel4RecordIndex++) {
            var itEditInputLevel4Record = editInputLevel4RecordList.get(itEditInputLevel4RecordIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "lastName")) {
                pathsForProperties.put("lastName", pathHere + itEditInputLevel4RecordIndex + "/lastName");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itEditInputLevel4Record, pathsForProperties, env));
        }

        return validationErrors;
    }
}
