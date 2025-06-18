package no.sikt.graphitron.example.server.frontgen.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import no.sikt.graphitron.example.server.frontgen.QueryBackedView;

import java.util.List;
import java.util.function.Function;

public class GenericQueryComponent<T, C> extends QueryComponent {
    private final String query;
    private final String rootField;
    private final Class<C> connectionClass;
    private final Function<C, List<T>> nodeExtractor;
    private final Function<List<T>, Grid<T>> gridCreator;
    private final String buttonText;

    private GenericQueryComponent(Builder<T, C> builder) {
        super(builder.queryView);
        this.query = builder.query;
        this.rootField = builder.rootField;
        this.connectionClass = builder.connectionClass;
        this.nodeExtractor = builder.nodeExtractor;
        this.gridCreator = builder.gridCreator;
        this.buttonText = builder.buttonText;

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

    public static class Builder<T, C> {
        private final QueryBackedView queryView;
        private String query;
        private String rootField;
        private Class<C> connectionClass;
        private Function<C, List<T>> nodeExtractor;
        private Function<List<T>, Grid<T>> gridCreator;
        private String buttonText;

        public Builder(QueryBackedView queryView) {
            this.queryView = queryView;
        }

        public Builder<T, C> query(String query) {
            this.query = query;
            return this;
        }

        public Builder<T, C> rootField(String rootField) {
            this.rootField = rootField;
            return this;
        }

        public Builder<T, C> connectionClass(Class<C> connectionClass) {
            this.connectionClass = connectionClass;
            return this;
        }

        public Builder<T, C> nodeExtractor(Function<C, List<T>> nodeExtractor) {
            this.nodeExtractor = nodeExtractor;
            return this;
        }

        public Builder<T, C> gridCreator(Function<List<T>, Grid<T>> gridCreator) {
            this.gridCreator = gridCreator;
            return this;
        }

        public Builder<T, C> buttonText(String buttonText) {
            this.buttonText = buttonText;
            return this;
        }

        public GenericQueryComponent<T, C> build() {
            return new GenericQueryComponent<>(this);
        }
    }
}