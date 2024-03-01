package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponse;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;

public class EditResponseTypeMapper {
    public static List<EditResponse> toGraphType(List<EditCustomerResponse1> editCustomerResponse1,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editResponseList = new ArrayList<EditResponse>();

        if (editCustomerResponse1 != null) {
            for (var itEditCustomerResponse1 : editCustomerResponse1) {
                if (itEditCustomerResponse1 == null) continue;
                var editResponse = new EditResponse();
                if (arguments.contains(pathHere + "id")) {
                    editResponse.setId(itEditCustomerResponse1.getId());
                }

                if (arguments.contains(pathHere + "firstName")) {
                    editResponse.setFirstName(itEditCustomerResponse1.getFirstName());
                }

                if (arguments.contains(pathHere + "email")) {
                    editResponse.setEmail(itEditCustomerResponse1.getSecretEmail());
                }

                editResponseList.add(editResponse);
            }
        }

        return editResponseList;
    }
}

