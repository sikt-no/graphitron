package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponse;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse2;

public class EditResponseTypeMapper {
    public static List<EditResponse> toGraphType(List<EditCustomerResponse2> editCustomerResponse2,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editResponseList = new ArrayList<EditResponse>();


        if (editCustomerResponse2 != null) {
            for (var itEditCustomerResponse2 : editCustomerResponse2) {
                if (itEditCustomerResponse2 == null) continue;
                var editResponse = new EditResponse();
                if (arguments.contains(pathHere + "id")) {
                    editResponse.setId(itEditCustomerResponse2.getId2());
                }

                var customer = itEditCustomerResponse2.getCustomer();
                if (customer != null && arguments.contains(pathHere + "customerC")) {
                    editResponse.setCustomerC(transform.customerRecordToGraphType(customer, pathHere + "customerC"));
                }

                editResponseList.add(editResponse);
            }
        }

        return editResponseList;
    }
}
