package no.sikt.graphitron.example.server.frontend;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.Route;
import no.sikt.graphitron.example.frontgen.components.QueryBackedView;
import no.sikt.graphitron.example.frontgen.components.QueryComponent;
import no.sikt.graphitron.example.frontgen.generate.generated.QueryComponents;
import no.sikt.graphitron.example.frontgen.graphql.GraphQLQueryAdapter;

import java.util.List;

@Route("")
public class MainView extends QueryBackedView {
    GraphQLQueryAdapter graphQLService;

    public MainView() {
        super(new GraphQLQueryAdapter());
        HorizontalLayout buttonBar = new HorizontalLayout();
        buttonBar.setWidthFull();
        buttonBar.setPadding(true);
        buttonBar.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);

        List<QueryComponent> components = createComponents();

        for (QueryComponent component : components) {
            Button button = createActionButton(component);
            buttonBar.add(button);
        }

        addClassName("centered-content");
        add(buttonBar);
    }

    private List<QueryComponent> createComponents() {
        return List.of(
                QueryComponents.createFilmComponent(this),
                QueryComponents.createCustomerComponent(this)
        );
    }

    private Button createActionButton(QueryComponent component) {
        Button button = new Button(component.getButtonText());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        button.addClickListener(e -> component.load());
        return button;
    }
}