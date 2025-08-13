import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.CustomerName;
import java.lang.Class;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

public class CustomersByIDQueryComponent extends GeneratedQueryComponent<Customer, CustomerConnection> {
    private TextField idField;

    private String idStoredValue;

    @Override
    public String getQuery() {
        return "query($id: ID!) { customersByID(id: $id, first: 100) { edges { node { name {  firstName lastName } email address {  id addressLine1 addressLine2 city {  id name countryName } zip phone } } } } }";
    }

    @Override
    public String getRootField() {
        return "customersByID";
    }

    @Override
    public Class<CustomerConnection> getConnectionClass() {
        return CustomerConnection.class;
    }

    @Override
    public Function<CustomerConnection, List<?>> getEdgesFunction() {
        return CustomerConnection::getEdges;
    }

    @Override
    public Function<Object, Customer> getNodeFunction() {
        return edge -> ((CustomerConnectionEdge) edge).getNode();
    }

    @Override
    public Function<List<Customer>, Grid<Customer>> getGridCreator() {
        return customers -> {
            Grid<Customer> grid = new Grid<>(Customer.class, false);
            grid.addColumn(Customer::getEmail)
                    .setHeader("Email")
                    .setFlexGrow(1);
            grid.addColumn(entity -> {
                        CustomerName name = entity.getName();
                        if (name != null) {
                            StringBuilder sb = new StringBuilder();
                            if (name.getFirstName() != null) {
                                sb.append(name.getFirstName());
                            }
                            if (name.getLastName() != null) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(name.getLastName());
                            }
                            return sb.length() > 0 ? sb.toString() : "N/A";
                        }
                        return "N/A";
                    })
                    .setHeader("Name")
                    .setFlexGrow(1);

            grid.addColumn(entity -> {
                        Address address = entity.getAddress();
                        if (address != null) {
                            StringBuilder sb = new StringBuilder();
                            if (address.getAddressLine1() != null) {
                                sb.append(address.getAddressLine1());
                            }
                            if (address.getAddressLine2() != null) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(address.getAddressLine2());
                            }
                            if (address.getZip() != null) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(address.getZip());
                            }
                            if (address.getPhone() != null) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(address.getPhone());
                            }
                            return sb.length() > 0 ? sb.toString() : "N/A";
                        }
                        return "N/A";
                    })
                    .setHeader("Address")
                    .setFlexGrow(1);

            grid.addColumn(entity -> {
                        Address address = entity.getAddress();
                        if (address != null) {
                            City city = address.getCity();
                            if (city != null) {
                                StringBuilder sb = new StringBuilder();
                                if (city.getName() != null) {
                                    sb.append(city.getName());
                                }
                                if (city.getCountryName() != null) {
                                    if (sb.length() > 0) sb.append(", ");
                                    sb.append(city.getCountryName());
                                }
                                return sb.length() > 0 ? sb.toString() : "N/A";
                            }
                        }
                        return "N/A";
                    })
                    .setHeader("Address   City")
                    .setFlexGrow(1);

            grid.setItems(customers);
            return grid;
        };
    }

    @Override
    public String getButtonText() {
        return "List CustomersByID";
    }

    @Override
    public boolean hasParameters() {
        return true;
    }

    @Override
    public VerticalLayout createInputSection() {
        VerticalLayout inputLayout = new VerticalLayout();
        if (idField != null) {
            idStoredValue = idField.getValue();
        }
        // Create new fields and restore values
        idField = new TextField("id");
        idField.setValue(idStoredValue != null ? idStoredValue : "");
        idField.setRequired(true);
        inputLayout.add(idField);
        return inputLayout;
    }

    @Override
    public Map<String, Object> getQueryVariables() {
        Map<String, Object> variables = new HashMap<>();
        if (idField != null && !idField.isEmpty()) {
            variables.put("id", idField.getValue());
        }
        return variables;
    }

    @Override
    public boolean validateInputs() {
        return (idField != null && !idField.isEmpty());
    }
}