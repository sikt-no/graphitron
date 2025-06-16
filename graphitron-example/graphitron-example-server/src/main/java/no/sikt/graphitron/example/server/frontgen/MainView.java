package no.sikt.graphitron.example.server.frontgen;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
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

        // Set the click listener for the button
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
                          }
                        }
                      }
                    }
                    """;
            Map<String, Object> response = graphQLService.executeQuery(query, Map.class);

            // Create a grid to display the data
            Grid<Map<String, Object>> grid = new Grid<>();
            grid.addColumn(customer -> customer.get("id")).setHeader("ID").setFlexGrow(0).setWidth("200px");
            grid.addColumn(customer -> customer.get("email")).setHeader("Email").setFlexGrow(1);
            grid.addColumn(customer -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> name = (Map<String, Object>) customer.get("name");
                    return name.get("firstName") + " " + name.get("lastName");
                } catch (Exception ex) {
                    return "N/A";
                }
            }).setHeader("Name").setFlexGrow(1);

            // Make the grid take full width
            grid.setWidthFull();

            if (response.containsKey("data")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> customers = (Map<String, Object>) data.get("customers");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> edges = (List<Map<String, Object>>) customers.get("edges");

                    // Extract the node data from each edge
                    List<Map<String, Object>> customerNodes = new ArrayList<>();
                    for (Map<String, Object> edge : edges) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> node = (Map<String, Object>) edge.get("node");
                        customerNodes.add(node);
                    }

                    // Clear any previous results
                    removeAll();
                    add(graphqlButton);

                    // Set the items and add the grid
                    grid.setItems(customerNodes);
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