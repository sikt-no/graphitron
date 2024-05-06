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
        var select = transform.getSelect();
        var editCustomerResponseList = new ArrayList<EditCustomerResponse>();

        if (editCustomerResponse1 != null) {
            for (var itEditCustomerResponse1 : editCustomerResponse1) {
                if (itEditCustomerResponse1 == null) continue;
                var editCustomerResponse = new EditCustomerResponse();
                if (select.contains(pathHere + "id")) {
                    editCustomerResponse.setId(itEditCustomerResponse1.getId());
                }

                editCustomerResponseList.add(editCustomerResponse);
            }
        }

        return editCustomerResponseList;
    }
}