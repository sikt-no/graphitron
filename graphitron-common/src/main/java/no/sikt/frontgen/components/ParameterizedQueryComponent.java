package no.sikt.frontgen.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ParameterizedQueryComponent<T, C> extends GenericQueryComponent<T, C> {
    private final GeneratedQueryComponent<T, C> parentComponent;
    private final Function<C, List<?>> edgesFunction;
    private final Function<Object, T> nodeFunction;
    private VerticalLayout mainLayout;
    private VerticalLayout inputSection;
    private Button executeButton;
    private VerticalLayout resultsSection;

    public ParameterizedQueryComponent(QueryBackedView queryView, GeneratedQueryComponent<T, C> parentComponent,
                                       String query, String rootField, Class<C> connectionClass,
                                       Function<C, List<T>> nodeExtractor, Function<List<T>, Grid<T>> gridCreator,
                                       String buttonText, Function<C, List<?>> edgesFunction,
                                       Function<Object, T> nodeFunction) {
        super(queryView, query, rootField, connectionClass, nodeExtractor, gridCreator, buttonText);
        this.parentComponent = parentComponent;
        this.edgesFunction = edgesFunction;
        this.nodeFunction = nodeFunction;

        initializeParameterizedComponent();
    }

    private void initializeParameterizedComponent() {
        removeAll();
        setSizeFull();

        mainLayout = new VerticalLayout();
        mainLayout.setSizeFull();
        mainLayout.setPadding(false);
        mainLayout.setSpacing(true);

        // Create permanent input section
        inputSection = parentComponent.createInputSection();
        if (inputSection.getComponentCount() > 0) {
            inputSection.getStyle().set("background-color", "var(--lumo-contrast-5pct)");
            inputSection.getStyle().set("border-radius", "var(--lumo-border-radius-m)");
            inputSection.getStyle().set("margin-bottom", "var(--lumo-space-m)");
            inputSection.getStyle().set("padding", "var(--lumo-space-m)");
            mainLayout.add(inputSection);
        }

        // Create permanent execute button
        executeButton = new Button(getButtonText());
        executeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        executeButton.addClickListener(e -> load());
        executeButton.getStyle().set("margin-bottom", "var(--lumo-space-m)");
        mainLayout.add(executeButton);

        resultsSection = new VerticalLayout();
        resultsSection.setPadding(false);
        resultsSection.setSpacing(false);
        resultsSection.setSizeFull();

        mainLayout.add(resultsSection);

        add(mainLayout);
    }

    @Override
    public void load() {
        if (!parentComponent.validateInputs()) {
            Notification.show("Please fill in all required fields");
            return;
        }

        // Get current values from existing fields
        Map<String, Object> currentValues = parentComponent.getQueryVariables();

        queryView.fetchWithVariables(
                query,
                currentValues,
                rootField,
                connectionClass,
                data -> {
                    // Only clear and update the results section
                    resultsSection.removeAll();

                    // Create the grid with proper sizing
                    Grid<T> grid = gridCreator.apply(data);
                    grid.setSizeFull();
                    grid.setHeightFull();
                    grid.setAllRowsVisible(true);

                    resultsSection.add(grid);
                    resultsSection.setSizeFull();

                    return grid;
                },
                edgesFunction,
                nodeFunction
        );
    }
}