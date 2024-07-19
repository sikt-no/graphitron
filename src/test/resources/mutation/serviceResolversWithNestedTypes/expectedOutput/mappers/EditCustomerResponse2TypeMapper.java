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
                    editCustomerResponse2.setCustomer(CustomerDBQueries.loadCustomerByIdsAsNode(transform.getCtx(), Set.of(customer.getId()), select.withPrefix(pathHere + "customer")).values().stream().findFirst().orElse(null));
                }

                var customerList = itEditCustomerResponse2.getCustomerList();
                if (customerList != null && select.contains(pathHere + "customerList")) {
                    var loadCustomerByIdsAsNode = CustomerDBQueries.loadCustomerByIdsAsNode(transform.getCtx(), customerList.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix(pathHere + "customerList"));
                    editCustomerResponse2.setCustomerList(customerList.stream().map(it -> loadCustomerByIdsAsNode.get(it.getId())).collect(Collectors.toList()));
                }

                editCustomerResponse2List.add(editCustomerResponse2);
            }
        }

        return editCustomerResponse2List;
    }
}
