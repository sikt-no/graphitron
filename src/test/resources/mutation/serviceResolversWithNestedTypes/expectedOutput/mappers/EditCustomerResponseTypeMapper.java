package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;

public class EditCustomerResponseTypeMapper {
    public static List<EditCustomerResponse> toGraphType(
            List<EditCustomerResponse1> editCustomerResponse1, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editCustomerResponseList = new ArrayList<EditCustomerResponse>();

        if (editCustomerResponse1 != null) {
            for (var itEditCustomerResponse1 : editCustomerResponse1) {
                if (itEditCustomerResponse1 == null) continue;
                var editCustomerResponse = new EditCustomerResponse();
                if (arguments.contains(pathHere + "id")) {
                    editCustomerResponse.setId(itEditCustomerResponse1.getId());
                }
                var editResponse2 = itEditCustomerResponse1.getEditResponse2();
                if (editResponse2 != null && arguments.contains(pathHere + "EditCustomerResponse2")) {
                    editCustomerResponse.setEditCustomerResponse2(transform.editCustomerResponse2ToGraphType(editResponse2, pathHere + "EditCustomerResponse2"));
                }

                var editResponse3 = itEditCustomerResponse1.getEditResponse3();
                if (editResponse3 != null && arguments.contains(pathHere + "EditCustomerResponse3")) {
                    editCustomerResponse.setEditCustomerResponse3(transform.editCustomerResponse3ToGraphType(editResponse3, pathHere + "EditCustomerResponse3"));
                }

                editCustomerResponseList.add(editCustomerResponse);
            }
        }

        return editCustomerResponseList;
    }
}
