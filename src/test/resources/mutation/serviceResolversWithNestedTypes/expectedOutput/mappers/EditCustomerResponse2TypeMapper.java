package fake.code.generated.mappers;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class EditCustomerResponse2TypeMapper {
    public static List<EditCustomerResponse2> toGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse2> editCustomerResponse2,
            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var select = transform.getSelect();
        var editCustomerResponse2List = new ArrayList<EditCustomerResponse2>();

        var customerDBQueries = new CustomerDBQueries();

        if (editCustomerResponse2 != null) {
            for (var itEditCustomerResponse2 : editCustomerResponse2) {
                if (itEditCustomerResponse2 == null) continue;
                var editCustomerResponse2 = new EditCustomerResponse2();
                if (arguments.contains(pathHere + "id")) {
                    editCustomerResponse2.setId(itEditCustomerResponse2.getId2());
                }

                var customer = itEditCustomerResponse2.getCustomer();
                if (customer != null && arguments.contains(pathHere + "customer")) {
                    editCustomerResponse2.setCustomer(customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(customer.getId()), select.withPrefix(pathHere + "customer")).values().stream().findFirst().orElse(null));
                }

                var customerList = itEditCustomerResponse2.getCustomerList();
                if (customerList != null && arguments.contains(pathHere + "customerList")) {
                    editCustomerResponse2.setCustomerList(customerDBQueries.loadCustomerByIdsAsNode(ctx, customerList.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix(pathHere + "customerList")));
                }

                editCustomerResponse2List.add(editCustomerResponse2);
            }
        }

        return editCustomerResponse2List;
    }
}
