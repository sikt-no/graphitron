package no.sikt.graphitron.example.frontgen.generate;

import com.vaadin.flow.component.grid.Grid;
import no.sikt.graphitron.example.frontgen.components.QueryBackedView;
import no.sikt.graphitron.example.frontgen.components.GenericQueryComponent;
import no.sikt.graphitron.example.frontgen.components.QueryComponent;

import java.util.List;
import java.util.function.Function;

/**
 * Utility class for creating query components
 */
public class QueryComponentBuilder {

    /**
     * Creates a component for a GraphQL connection-based query
     */
    public static <T, C> QueryComponent createConnectionComponent(
            QueryBackedView view,
            String query,
            String rootField,
            Class<C> connectionClass,
            Function<C, List<?>> getEdgesFunc,
            Function<Object, T> getNodeFunc,
            Function<List<T>, Grid<T>> gridCreator,
            String buttonText) {

        return new GenericQueryComponent.Builder<T, C>(view)
                .query(query)
                .rootField(rootField)
                .connectionClass(connectionClass)
                .nodeExtractor(connection -> {
                    List<?> edges = getEdgesFunc.apply(connection);
                    if (edges == null) return List.of();
                    return edges.stream()
                            .map(getNodeFunc)
                            .map(node -> (T) node)
                            .toList();
                })
                .gridCreator(gridCreator)
                .buttonText(buttonText)
                .build();
    }
}
