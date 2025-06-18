package no.sikt.graphitron.example.server.frontgen;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import jakarta.inject.Inject;
import no.sikt.graphitron.example.server.frontgen.graphql.GraphQLQueryAdapter;
import no.sikt.graphitron.example.server.frontgen.graphql.GraphQLResponse;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class QueryBackedView extends VerticalLayout {

    @Inject
    GraphQLQueryAdapter graphQLService;

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
            Button refreshButton = new Button(buttonText);
            refreshButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            refreshButton.addClickListener(e -> buttonAction.run());
            add(refreshButton);

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
            Button refreshButton = new Button(buttonText);
            refreshButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            refreshButton.addClickListener(e -> buttonAction.run());
            add(refreshButton);
            add(new Paragraph("Error executing query: " + ex.getMessage()));
        }
    }
}
