package no.sikt.graphitron.example.frontgen.generate.generated;

import com.vaadin.flow.component.grid.Grid;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.function.Function;
import no.sikt.graphitron.example.frontgen.generate.GeneratedQueryComponent;
import no.sikt.graphitron.example.generated.graphitron.model.Customer;
import no.sikt.graphitron.example.generated.graphitron.model.CustomerConnection;
import no.sikt.graphitron.example.generated.graphitron.model.CustomerConnectionEdge;

public class CustomerQueryComponent extends GeneratedQueryComponent<Customer, CustomerConnection> {
    @Override
    protected String getQuery() {
        return "query { customers(first: 100) { edges { node { id email name { firstName lastName } address { addressLine1 addressLine2 city { name countryName } } } } } }";
    }

    @Override
    protected String getRootField() {
        return "customers";
    }

    @Override
    protected Function<CustomerConnection, List> getEdgesFunction() {
        return CustomerConnection::getEdges;
    }

    @Override
    protected Function<Object, Customer> getNodeFunction() {
        return edge -> ((CustomerConnectionEdge) edge).getNode();
    }

    @Override
    protected Function<List<Customer>, Grid<Customer>> getGridCreator() {
        return customers -> {
            Grid<Customer> grid = new Grid<>(Customer.class, false);
            grid.addColumn(Customer::getId)
                    .setHeader("ID")
                    .setFlexGrow(1);
            grid.addColumn(Customer::getEmail)
                    .setHeader("Email")
                    .setFlexGrow(1);
            grid.addColumn(customer -> {
                        CustomerName name = customer.getName();
                        return name != null ? name.getFirstName() + " " + name.getLastName() : "N/A";
                    })
                    .setHeader("Full Name")
                    .setFlexGrow(1);

            grid.addColumn(customer -> {
                        Address address = customer.getAddress();
                        if (address != null) {
                            StringBuilder addressText = new StringBuilder();
                            if (address.getAddressLine1() != null) {
                                addressText.append(address.getAddressLine1());
                            }
                            if (address.getAddressLine2() != null && !address.getAddressLine2().isEmpty()) {
                                addressText.append(", ").append(address.getAddressLine2());
                            }
                            if (address.getCity() != null) {
                                addressText.append(", ").append(address.getCity().getName());
                                if (address.getCity().getCountryName() != null) {
                                    addressText.append(", ").append(address.getCity().getCountryName());
                                }
                            }
                            return addressText.toString();
                        }
                        return "N/A";
                    })
                    .setHeader("Address")
                    .setFlexGrow(2);

            grid.setItems(customers);
            return grid;
        };
    }

    @Override
    protected String getButtonText() {
        return "List Customers";
    }
}