package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponseWithCustomer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse2;

public class EditResponseWithCustomerTypeMapper {
    public static List<EditResponseWithCustomer> toGraphType(
            List<EditCustomerResponse2> editCustomerResponse2, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponseWithCustomerList = new ArrayList<EditResponseWithCustomer>();

        if (editCustomerResponse2 != null) {
            for (var itEditCustomerResponse2 : editCustomerResponse2) {
                if (itEditCustomerResponse2 == null) continue;
                var editResponseWithCustomer = new EditResponseWithCustomer();
                if (select.contains(pathHere + "id")) {
                    editResponseWithCustomer.setId(itEditCustomerResponse2.getId2());
                }

                var customer = itEditCustomerResponse2.getCustomer();
                if (customer != null && select.contains(pathHere + "customerC")) {
                    editResponseWithCustomer.setCustomerC(transform.customerRecordToGraphType(customer, pathHere + "customerC"));
                }

                editResponseWithCustomerList.add(editResponseWithCustomer);
            }
        }

        return editResponseWithCustomerList;
    }
}

