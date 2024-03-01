package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse3;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;

public class EditCustomerResponse3TypeMapper {
    public static List<EditCustomerResponse3> toGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse3> editCustomerResponse3,
            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editCustomerResponse3List = new ArrayList<EditCustomerResponse3>();

        if (editCustomerResponse3 != null) {
            for (var itEditCustomerResponse3 : editCustomerResponse3) {
                if (itEditCustomerResponse3 == null) continue;
                var editCustomerResponse3 = new EditCustomerResponse3();
                if (arguments.contains(pathHere + "id")) {
                    editCustomerResponse3.setId(itEditCustomerResponse3.getId3());
                }

                var customer3 = itEditCustomerResponse3.getCustomer3();
                if (customer3 != null && arguments.contains(pathHere + "customer")) {
                    editCustomerResponse3.setCustomer(transform.customerRecordToGraphType(customer3, pathHere + "customer"));
                }

                editCustomerResponse3List.add(editCustomerResponse3);
            }
        }

        return editCustomerResponse3List;
    }
}
