import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerConnection;
import fake.graphql.example.model.CustomerConnectionEdge;
import fake.graphql.example.model.CustomerName;
import java.lang.Boolean;
import java.lang.Class;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

public class CustomersByInputQueryComponent extends GeneratedQueryComponent<Customer, CustomerConnection> {
    private TextField inputIdField;

    private String inputIdStoredValue;

    private TextField inputEmailField;

    private String inputEmailStoredValue;

    private Checkbox inputIsActiveField;

    private Boolean inputIsActiveStoredValue;

    @Override
    public String getQuery() {
        return "query($input: CustomerInput!) { customersByInput(input: $input, first: 100) { edges { node { name {  firstName lastName } email address {  id addressLine1 addressLine2 city {  id name countryName } zip phone } } } } }";
    }

    @Override
    public String getRootField() {
        return "customersByInput";
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
        return "List CustomersByInput";
    }

    @Override
    public boolean hasParameters() {
        return true;
    }

    @Override
    public VerticalLayout createInputSection() {
        VerticalLayout inputLayout = new VerticalLayout();
        if (inputIdField != null) {
            inputIdStoredValue = inputIdField.getValue();
        }
        if (inputEmailField != null) {
            inputEmailStoredValue = inputEmailField.getValue();
        }
        if (inputIsActiveField != null) {
            inputIsActiveStoredValue = inputIsActiveField.getValue();
        }
        // Create new fields and restore values
        // Fields for input
        inputIdField = new TextField("input id");
        inputIdField.setValue(inputIdStoredValue != null ? inputIdStoredValue : "");
        inputIdField.setRequired(true);
        inputLayout.add(inputIdField);
        inputEmailField = new TextField("input email");
        inputEmailField.setValue(inputEmailStoredValue != null ? inputEmailStoredValue : "");
        inputEmailField.setRequired(true);
        inputLayout.add(inputEmailField);
        inputIsActiveField = new Checkbox("input isActive");
        inputIsActiveField.setValue(inputIsActiveStoredValue != null ? inputIsActiveStoredValue : false);
        inputLayout.add(inputIsActiveField);
        return inputLayout;
    }

    @Override
    public Map<String, Object> getQueryVariables() {
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> inputObj = new HashMap<>();
        boolean inputHasValues = false;
        if (inputIdField != null && !inputIdField.isEmpty()) {
            inputObj.put("id", inputIdField.getValue());
            inputHasValues = true;
        }
        if (inputEmailField != null && !inputEmailField.isEmpty()) {
            inputObj.put("email", inputEmailField.getValue());
            inputHasValues = true;
        }
        if (inputIsActiveField != null) {
            inputObj.put("isActive", inputIsActiveField.getValue());
            inputHasValues = true;
        }
        if (inputHasValues) {
            variables.put("input", inputObj);
        }
        return variables;
    }

    @Override
    public boolean validateInputs() {
        return (inputIdField != null && !inputIdField.isEmpty() || inputEmailField != null && !inputEmailField.isEmpty());
    }
}