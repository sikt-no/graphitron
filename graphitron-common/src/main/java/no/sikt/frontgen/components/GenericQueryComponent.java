package no.sikt.frontgen.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;

import java.util.List;
import java.util.function.Function;

public class GenericQueryComponent<T, C> extends QueryComponent {
    protected final String query;
    protected final String rootField;
    protected final Class<C> connectionClass;
    protected final Function<C, List<T>> nodeExtractor;
    protected final Function<List<T>, Grid<T>> gridCreator;
    protected final String buttonText;

    public GenericQueryComponent(QueryBackedView queryView, String query, String rootField,
                                 Class<C> connectionClass, Function<C, List<T>> nodeExtractor,
                                 Function<List<T>, Grid<T>> gridCreator, String buttonText) {
        super(queryView);
        this.query = query;
        this.rootField = rootField;
        this.connectionClass = connectionClass;
        this.nodeExtractor = nodeExtractor;
        this.gridCreator = gridCreator;
        this.buttonText = buttonText;

        setSizeFull();
    }

    @Override
    public void load() {
        queryView.fetch(
                query,
                rootField,
                connectionClass,
                (Function<List<T>, Component>) (Function<?, ?>) gridCreator,
                nodeExtractor,
                getButtonText(),
                getLoadAction()
        );
    }

    @Override
    public String getButtonText() {
        return buttonText;
    }

    @Override
    public Runnable getLoadAction() {
        return this::load;
    }
}