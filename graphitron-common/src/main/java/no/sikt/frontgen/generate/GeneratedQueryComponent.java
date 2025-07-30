package no.sikt.frontgen.generate;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class GeneratedQueryComponent<T, C> {

    protected abstract String getQuery();
    protected abstract String getRootField();
    protected abstract Class<C> getConnectionClass();
    protected abstract Function<C, List<?>> getEdgesFunction();
    protected abstract Function<Object, T> getNodeFunction();
    protected abstract Function<List<T>, Grid<T>> getGridCreator();
    protected abstract String getButtonText();

    // Methods for input handling - can be overridden by generated classes
    protected Map<String, Object> getQueryVariables() {
        return new HashMap<>();
    }

    protected VerticalLayout createInputSection() {
        return new VerticalLayout();
    }

    protected boolean validateInputs() {
        return true;
    }

    protected boolean hasParameters() {
        return false;
    }

    public QueryComponent createComponent(QueryBackedView view) {
        return new ParameterizedQueryComponent(view);
    }

    private class ParameterizedQueryComponent extends QueryComponent {
        private VerticalLayout inputSection;

        public ParameterizedQueryComponent(QueryBackedView queryView) {
            super(queryView);
            initializeComponent();
        }

        private void initializeComponent() {
            setSizeFull();

            // Add input section if the component has parameters
            if (hasParameters()) {
                inputSection = createInputSection();
                if (inputSection.getComponentCount() > 0) {
                    add(inputSection);
                }
            }

            // Add execute button
            Button executeButton = new Button(getButtonText());
            executeButton.addClickListener(e -> load());
            add(executeButton);
        }

        @Override
        public void load() {
            if (!validateInputs()) {
                Notification.show("Please fill in all required fields");
                return;
            }

            if (hasParameters()) {
                queryView.fetchWithVariables(
                        getQuery(),
                        getQueryVariables(),
                        getRootField(),
                        getConnectionClass(),
                        customers -> getGridCreator().apply(customers),
                        getEdgesFunction(),
                        getNodeFunction()
                );
            } else {
                // Use the existing fetch method for non-parameterized queries
                queryView.fetch(
                        getQuery(),
                        getRootField(),
                        getConnectionClass(),
                        customers -> getGridCreator().apply(customers),
                        connection -> {
                            List<?> edges = getEdgesFunction().apply(connection);
                            return edges.stream().map(getNodeFunction()).toList();
                        },
                        getButtonText(),
                        this::load
                );
            }
        }

        @Override
        public String getButtonText() {
            return GeneratedQueryComponent.this.getButtonText();
        }

        @Override
        public Runnable getLoadAction() {
            return this::load;
        }
    }
}