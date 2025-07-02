package no.sikt.frontgen.generate;

import com.vaadin.flow.component.grid.Grid;
import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;

import java.util.List;
import java.util.function.Function;

/**
 * Base class for generated query components
 */
public abstract class GeneratedQueryComponent<T, C> {

    /**
     * Creates a QueryComponent instance
     */
    public QueryComponent createComponent(QueryBackedView view) {
        return QueryComponentBuilder.createConnectionComponent(
            view,
            getQuery(),
            getRootField(),
            getConnectionClass(),
            getEdgesFunction(),
            getNodeFunction(),
            getGridCreator(),
            getButtonText()
        );
    }

    /**
     * Returns the GraphQL query string
     */
    protected abstract String getQuery();

    /**
     * Returns the root field in the GraphQL response
     */
    protected abstract String getRootField();

    /**
     * Returns the connection class
     */
    protected abstract Class<C> getConnectionClass();

    /**
     * Returns the function to extract edges from the connection
     */
    protected abstract Function<C, List<?>> getEdgesFunction();

    /**
     * Returns the function to extract node from an edge
     */
    protected abstract Function<Object, T> getNodeFunction();

    /**
     * Returns the grid creator function
     */
    protected abstract Function<List<T>, Grid<T>> getGridCreator();

    /**
     * Returns the button text
     */
    protected abstract String getButtonText();
}
