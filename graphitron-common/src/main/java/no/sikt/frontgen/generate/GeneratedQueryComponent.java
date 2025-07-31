package no.sikt.frontgen.generate;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import no.sikt.frontgen.components.GenericQueryComponent;
import no.sikt.frontgen.components.ParameterizedQueryComponent;
import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class GeneratedQueryComponent<T, C> {

    public abstract String getQuery();
    public abstract String getRootField();
    public abstract Class<C> getConnectionClass();
    public abstract Function<C, List<?>> getEdgesFunction();
    public abstract Function<Object, T> getNodeFunction();
    public abstract Function<List<T>, Grid<T>> getGridCreator();
    public abstract String getButtonText();

    // Methods for input handling - can be overridden by generated classes
    public Map<String, Object> getQueryVariables() {
        return new HashMap<>();
    }

    public VerticalLayout createInputSection() {
        return new VerticalLayout();
    }

    public boolean validateInputs() {
        return true;
    }

    public boolean hasParameters() {
        return false;
    }

    public QueryComponent createComponent(QueryBackedView view) {
        if (hasParameters()) {
            return new ParameterizedQueryComponent<>(
                    view, this, getQuery(), getRootField(), getConnectionClass(),
                    connection -> {
                        List<?> edges = getEdgesFunction().apply(connection);
                        if (edges == null) return List.of();
                        return edges.stream()
                                .map(getNodeFunction())
                                .map(node -> (T) node)
                                .toList();
                    },
                    getGridCreator(), getButtonText(), getEdgesFunction(), getNodeFunction()
            );
        } else {
            return new GenericQueryComponent<>(
                    view, getQuery(), getRootField(), getConnectionClass(),
                    connection -> {
                        List<?> edges = getEdgesFunction().apply(connection);
                        if (edges == null) return List.of();
                        return edges.stream()
                                .map(getNodeFunction())
                                .map(node -> (T) node)
                                .toList();
                    },
                    getGridCreator(), getButtonText()
            );
        }
    }

}