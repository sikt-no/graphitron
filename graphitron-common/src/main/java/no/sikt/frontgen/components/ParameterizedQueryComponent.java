package no.sikt.frontgen.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

import java.util.List;
import java.util.function.Function;

public class ParameterizedQueryComponent<T, C> extends GenericQueryComponent<T, C> {
    private final GeneratedQueryComponent<T, C> parentComponent;
    private final Function<C, List<?>> edgesFunction;
    private final Function<Object, T> nodeFunction;

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

        VerticalLayout inputSection = parentComponent.createInputSection();
        if (inputSection.getComponentCount() > 0) {
            add(inputSection);
        }

        Button executeButton = new Button(getButtonText());
        executeButton.addClickListener(e -> load());
        add(executeButton);
    }

    @Override
    public void load() {
        if (!parentComponent.validateInputs()) {
            Notification.show("Please fill in all required fields");
            return;
        }

        queryView.fetchWithVariables(
                query,
                parentComponent.getQueryVariables(),
                rootField,
                connectionClass,
                gridCreator::apply,
                edgesFunction,
                nodeFunction
        );
    }
}