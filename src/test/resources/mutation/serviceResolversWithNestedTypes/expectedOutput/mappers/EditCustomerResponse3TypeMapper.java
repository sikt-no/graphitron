package fake.code.generated.mappers;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse3;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class EditCustomerResponse3TypeMapper {
    public static List<EditCustomerResponse3> toGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse3> editCustomerResponse3,
            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponse3List = new ArrayList<EditCustomerResponse3>();

        var customerDBQueries = new CustomerDBQueries();

        if (editCustomerResponse3 != null) {
            for (var itEditCustomerResponse3 : editCustomerResponse3) {
                if (itEditCustomerResponse3 == null) continue;
                var editCustomerResponse3 = new EditCustomerResponse3();
                if (select.contains(pathHere + "id")) {
                    editCustomerResponse3.setId(itEditCustomerResponse3.getId3());
                }

                var customer3 = itEditCustomerResponse3.getCustomer3();
                if (customer3 != null && select.contains(pathHere + "customer")) {
                    editCustomerResponse3.setCustomer(customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(customer3.getId()), select.withPrefix(pathHere + "customer")).values().stream().findFirst().orElse(null));
                }

                var edit4 = itEditCustomerResponse3.getEdit4();
                if (edit4 != null && select.contains(pathHere + "EditCustomerResponse4")) {
                    editCustomerResponse3.setEditCustomerResponse4(transform.editCustomerResponse4ToGraphType(edit4, pathHere + "EditCustomerResponse4"));
                }

                editCustomerResponse3List.add(editCustomerResponse3);
            }
        }

        return editCustomerResponse3List;
    }
}

