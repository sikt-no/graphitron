package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;

public class EditCustomerResponse2TypeMapper {
    public static List<EditCustomerResponse2> toGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse2> editCustomerResponse2,
            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponse2List = new ArrayList<EditCustomerResponse2>();

        if (editCustomerResponse2 != null) {
            for (var itEditCustomerResponse2 : editCustomerResponse2) {
                if (itEditCustomerResponse2 == null) continue;
                var editCustomerResponse2 = new EditCustomerResponse2();
                if (select.contains(pathHere + "id")) {
                    editCustomerResponse2.setId(itEditCustomerResponse2.getId2());
                }

                var customer = itEditCustomerResponse2.getCustomer();
                if (customer != null && select.contains(pathHere + "customer")) {
                    editCustomerResponse2.setCustomer(transform.customerRecordToGraphType(customer, pathHere + "customer"));
                }

                editCustomerResponse2List.add(editCustomerResponse2);
            }
        }

        return editCustomerResponse2List;
    }
}
