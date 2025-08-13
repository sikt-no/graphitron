package no.sikt.graphitron.example.server.frontend;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;
import no.sikt.frontgen.components.ParameterizedQueryComponent;
import no.sikt.frontgen.graphql.GraphQLQueryAdapter;
import no.sikt.graphitron.example.generated.graphitron.frontend.QueryComponents;

import java.util.List;

@Route("")
public class MainView extends QueryBackedView {

    public MainView() {
        super(new GraphQLQueryAdapter());

        VerticalLayout mainLayout = new VerticalLayout();
        mainLayout.setSizeFull();
        mainLayout.setPadding(true);
        mainLayout.setSpacing(true);

        H2 header = new H2("Generated Components");
        header.getStyle().set("margin-bottom", "1em");

        List<QueryComponent> components = QueryComponents.getComponents(this);

        FlexLayout cardLayout = new FlexLayout();
        cardLayout.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cardLayout.setJustifyContentMode(FlexComponent.JustifyContentMode.START);
        cardLayout.getStyle().set("gap", "1rem");

        for (QueryComponent component : components) {
            cardLayout.add(createComponentCard(component));
        }

        mainLayout.add(header, cardLayout);
        add(mainLayout);
        addClassName("main-layout");
    }

    private Card createComponentCard(QueryComponent component) {
        Card card = new Card();
        card.setWidth("300px");
        card.addClassName("component-card");

        String displayName = component.getButtonText().replaceFirst("^List\\s+", "");
        String formattedName = formatComponentName(displayName);

        VerticalLayout cardContent = new VerticalLayout();
        cardContent.setPadding(false);
        cardContent.setSpacing(false);

        H3 title = new H3(formattedName);
        title.getStyle().set("margin", "1rem");
        title.getStyle().set("word-wrap", "break-word");
        title.getStyle().set("line-height", "1.2");

        Button launchButton = new Button("View", e -> navigateToComponent(component));
        launchButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        launchButton.getStyle().set("margin", "1rem");

        cardContent.add(title, launchButton);
        card.add(cardContent);

        return card;
    }

    private String formatComponentName(String name) {
        return name.replaceAll("([A-Z])", " $1").trim();
    }

    private void navigateToComponent(QueryComponent component) {
        removeAll();
        add(component);

        // Only autoload for non-parameterized components
        // Parameterized components will show their input form and wait for user action
        if (!(component instanceof ParameterizedQueryComponent)) {
            component.load();
        }
    }
}