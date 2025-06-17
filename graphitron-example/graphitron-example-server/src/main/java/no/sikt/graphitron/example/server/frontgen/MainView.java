package no.sikt.graphitron.example.server.frontgen;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import no.sikt.graphitron.example.generated.graphitron.model.Address;
import no.sikt.graphitron.example.generated.graphitron.model.Customer;
import no.sikt.graphitron.example.generated.graphitron.model.CustomerConnection;
import no.sikt.graphitron.example.generated.graphitron.model.CustomerConnectionEdge;
import no.sikt.graphitron.example.generated.graphitron.model.CustomerName;
import no.sikt.graphitron.example.server.frontgen.graphql.GraphQLResponse;
import no.sikt.graphitron.example.server.frontgen.graphql.GraphQLService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Route("")
public class MainView extends VerticalLayout {

    @Inject
    GraphQLService graphQLService;

    public MainView() {
        Button graphqlButton = new Button("List customers");
        Jsonb jsonb = JsonbBuilder.create();

        graphqlButton.addClickListener(e -> {
            String query =
                    """
                    query CustomersBasic {
                      customers(first: 10) {
                        edges {
                          node {
                            id
                            email
                            name {
                              firstName
                              lastName
                            }
                            address {
                              addressLine1
                              addressLine2
                              city {
                                name
                                countryName
                              }
                            }
                          }
                        }
                      }
                    }
                    """;

            // Use raw type for now to parse the JSON structure
            GraphQLResponse<Map<String, Object>> response = graphQLService.executeQuery(query, GraphQLResponse.class);

            Grid<Customer> grid = new Grid<>(Customer.class);
            grid.setColumns("id", "email");

            grid.addColumn(customer -> {
                CustomerName name = customer.getName();
                return name != null ? name.getFirstName() + " " + name.getLastName() : "N/A";
            }).setHeader("Full Name").setFlexGrow(1);

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
            }).setHeader("Address").setFlexGrow(1);

            // Make the grid take full width
            grid.setWidthFull();

            if (response.getData() != null) {
                try {
                    Map<String, Object> data = response.getData();
                    // Convert the customers part to JSON and then to CustomerConnection
                    CustomerConnection customers = jsonb.fromJson(
                            jsonb.toJson(data.get("customers")),
                            CustomerConnection.class
                    );

                    List<CustomerConnectionEdge> edges = customers.getEdges();

                    List<Customer> customerList = new ArrayList<>();
                    for (CustomerConnectionEdge edge : edges) {
                        customerList.add(edge.getNode());
                    }

                    // Clear any previous results
                    removeAll();
                    add(graphqlButton);

                    grid.setItems(customerList);
                    add(grid);
                } catch (Exception ex) {
                    removeAll();
                    add(graphqlButton);
                    add(new Paragraph("Error parsing GraphQL response: " + ex.getMessage()));
                }
            } else {
                removeAll();
                add(graphqlButton);
                add(new Paragraph("GraphQL error occurred"));
            }
        });

        graphqlButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        // Use custom CSS classes to apply styling.
        addClassName("centered-content");

        add(graphqlButton);
    }
}