package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditAddressResponse;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerAddressResponse;

public class EditAddressResponseTypeMapper {
    public static List<EditAddressResponse> toGraphType(
            List<EditCustomerAddressResponse> editCustomerAddressResponse, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editAddressResponseList = new ArrayList<EditAddressResponse>();

        if (editCustomerAddressResponse != null) {
            for (var itEditCustomerAddressResponse : editCustomerAddressResponse) {
                if (itEditCustomerAddressResponse == null) continue;
                var editAddressResponse = new EditAddressResponse();
                if (arguments.contains(pathHere + "id")) {
                    editAddressResponse.setId(itEditCustomerAddressResponse.getId());
                }

                if (arguments.contains(pathHere + "addressId")) {
                    editAddressResponse.setAddressId(itEditCustomerAddressResponse.getAddressId());
                }

                editAddressResponseList.add(editAddressResponse);
            }
        }

        return editAddressResponseList;
    }
}
