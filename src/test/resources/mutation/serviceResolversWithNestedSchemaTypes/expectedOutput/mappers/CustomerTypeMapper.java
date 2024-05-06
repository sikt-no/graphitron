package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Name;
import fake.graphql.example.model.NameLevels;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class CustomerTypeMapper {
    public static List<Customer> recordToGraphType(List<CustomerRecord> customerRecord, String path,
                                                   RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerList = new ArrayList<Customer>();

        if (customerRecord != null) {
            for (var itCustomerRecord : customerRecord) {
                if (itCustomerRecord == null) continue;
                var customer = new Customer();
                if (select.contains(pathHere + "id")) {
                    customer.setId(itCustomerRecord.getId());
                }

                if (select.contains(pathHere + "name")) {
                    var customer_name = new Name();
                    if (select.contains(pathHere + "name/first")) {
                        customer_name.setFirst(itCustomerRecord.getFirstName());
                    }

                    if (select.contains(pathHere + "name/last")) {
                        customer_name.setLast(itCustomerRecord.getLastName());
                    }

                    customer.setName(customer_name);
                }

                if (select.contains(pathHere + "nameTwoLevels")) {
                    var customer_nameTwoLevels = new NameLevels();
                    if (select.contains(pathHere + "nameTwoLevels/name")) {
                        var nameLevels_name = new Name();
                        if (select.contains(pathHere + "nameTwoLevels/name/first")) {
                            nameLevels_name.setFirst(itCustomerRecord.getFirstName());
                        }

                        if (select.contains(pathHere + "nameTwoLevels/name/last")) {
                            nameLevels_name.setLast(itCustomerRecord.getLastName());
                        }
                        customer_nameTwoLevels.setName(nameLevels_name);
                    }
                    customer.setNameTwoLevels(customer_nameTwoLevels);
                }

                if (select.contains(pathHere + "postalCode")) {
                    customer.setPostalCode(itCustomerRecord.getPostalCode());
                }

                if (select.contains(pathHere + "lastUpdate")) {
                    customer.setLastUpdate(itCustomerRecord.getLastUpdate());
                }
                customerList.add(customer);
            }
        }

        return customerList;
    }
}
