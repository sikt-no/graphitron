package no.sikt.frontgen.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import no.sikt.frontgen.graphql.GraphQLQueryAdapter;
import no.sikt.frontgen.graphql.GraphQLResponse;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class QueryBackedView extends VerticalLayout {

    private final GraphQLQueryAdapter graphQLService;

    public QueryBackedView(GraphQLQueryAdapter graphQLService) {
        this.graphQLService = graphQLService;
    }

    public <T, R> void fetch(
            String query,
            String rootField,
            Class<T> connectionClass,
            Function<List<R>, Component> displayFunction,
            Function<T, List<R>> dataExtractor,
            String buttonText,
            Runnable buttonAction) {

        try {
            GraphQLResponse<Map<String, Object>> response = graphQLService.executeQuery(query, GraphQLResponse.class);

            removeAll();

            // Add page header
            String viewName = buttonText.replaceFirst("^List\\s+", "");
            add(new H2(viewName));

            HorizontalLayout buttonLayout = new HorizontalLayout();
            buttonLayout.setWidthFull();

            // Add back button that navigates to MainView
            Button backButton = new Button("Back", e -> {
                getUI().ifPresent(ui -> ui.getPage().setLocation("/frontgen"));
            });
            backButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);

            // Rename action button to "Refresh"
            Button actionButton = new Button("Refresh");
            actionButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            actionButton.addClickListener(e -> buttonAction.run());

            buttonLayout.add(backButton, actionButton);
            buttonLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);

            add(buttonLayout);

            if (response.getData() != null) {
                Map<String, Object> data = response.getData();
                T connection = ResolverHelpers.transformDTO(data.get(rootField), connectionClass);

                List<R> items = dataExtractor.apply(connection);
                if (items != null && !items.isEmpty()) {
                    add(displayFunction.apply(items));
                } else {
                    add(new Paragraph("No data returned"));
                }
            } else if (response.hasErrors()) {
                add(new Paragraph("GraphQL error: " + response.getErrorMessage()));
            } else {
                add(new Paragraph("No data returned"));
            }
        } catch (Exception ex) {
            removeAll();

            // Add page header even in error state
            String viewName = buttonText.replaceFirst("^List\\s+", "");
            add(new H2(viewName));

            HorizontalLayout buttonLayout = new HorizontalLayout();
            buttonLayout.setWidthFull();

            Button backButton = new Button("Back", e -> {
                getUI().ifPresent(ui -> ui.getPage().setLocation("/frontgen"));
            });
            backButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);

            Button actionButton = new Button("Refresh");
            actionButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            actionButton.addClickListener(e -> buttonAction.run());

            buttonLayout.add(backButton, actionButton);
            buttonLayout.setJustifyContentMode(JustifyContentMode.BETWEEN);

            add(buttonLayout);
            add(new Paragraph("Error executing query: " + ex.getMessage()));
        }
    }

    public <T, R> void fetchWithVariables(
            String query,
            Map<String, Object> variables,
            String rootField,
            Class<T> connectionClass,
            Function<List<R>, Component> displayFunction,
            Function<T, List<?>> edgesExtractor,
            Function<Object, R> nodeExtractor) {

        try {
            GraphQLResponse<Map<String, Object>> response = graphQLService.executeQueryWithVariables(query, variables, GraphQLResponse.class);

            removeAll();

            // Add page header
            String viewName = "Query Results";
            add(new H2(viewName));

            HorizontalLayout buttonLayout = new HorizontalLayout();
            buttonLayout.setWidthFull();

            Button backButton = new Button("Back", e -> {
                getUI().ifPresent(ui -> ui.getPage().setLocation("/frontgen"));
            });
            backButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);

            buttonLayout.add(backButton);
            buttonLayout.setJustifyContentMode(JustifyContentMode.START);

            add(buttonLayout);

            if (response.getData() != null) {
                Map<String, Object> data = response.getData();
                T connection = ResolverHelpers.transformDTO(data.get(rootField), connectionClass);

                List<?> edges = edgesExtractor.apply(connection);
                if (edges != null && !edges.isEmpty()) {
                    List<R> items = edges.stream()
                            .map(nodeExtractor)
                            .toList();
                    add(displayFunction.apply(items));
                } else {
                    add(new Paragraph("No data returned"));
                }
            } else if (response.hasErrors()) {
                add(new Paragraph("GraphQL error: " + response.getErrorMessage()));
            } else {
                add(new Paragraph("No data returned"));
            }
        } catch (Exception ex) {
            removeAll();
            add(new H2("Error"));
            add(new Paragraph("Error executing query: " + ex.getMessage()));
        }
    }
}
