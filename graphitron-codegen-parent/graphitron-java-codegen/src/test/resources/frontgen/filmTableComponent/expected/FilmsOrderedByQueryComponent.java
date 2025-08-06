import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.QueryFilmsConnection;
import fake.graphql.example.model.QueryFilmsConnectionEdge;
import java.lang.Class;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

public class FilmsOrderedByQueryComponent extends GeneratedQueryComponent<Film, QueryFilmsConnection> {
    private TextField orderByOrderByFieldField;

    private TextField orderByDirectionField;

    @Override
    public String getQuery() {
        return "query($orderBy: FilmsOrderByInput) { filmsOrderedBy(orderBy: $orderBy, first: 100) { edges { node { id title } } } }";
    }

    @Override
    public String getRootField() {
        return "filmsOrderedBy";
    }

    @Override
    public Class<QueryFilmsConnection> getConnectionClass() {
        return QueryFilmsConnection.class;
    }

    @Override
    public Function<QueryFilmsConnection, List<?>> getEdgesFunction() {
        return QueryFilmsConnection::getEdges;
    }

    @Override
    public Function<Object, Film> getNodeFunction() {
        return edge -> ((QueryFilmsConnectionEdge) edge).getNode();
    }

    @Override
    public Function<List<Film>, Grid<Film>> getGridCreator() {
        return films -> {
            Grid<Film> grid = new Grid<>(Film.class, false);
            grid.addColumn(Film::getId)
                    .setHeader("Id")
                    .setFlexGrow(1);
            grid.addColumn(Film::getTitle)
                    .setHeader("Title")
                    .setFlexGrow(1);
            grid.setItems(films);
            return grid;
        };
    }

    @Override
    public String getButtonText() {
        return "List FilmsOrderedBy";
    }

    @Override
    public boolean hasParameters() {
        return true;
    }

    @Override
    public VerticalLayout createInputSection() {
        VerticalLayout inputLayout = new VerticalLayout();
        // Fields for orderBy;
        orderByOrderByFieldField = new TextField("orderBy orderByField");
        orderByOrderByFieldField.setRequired(true);
        inputLayout.add(orderByOrderByFieldField);
        orderByDirectionField = new TextField("orderBy direction");
        orderByDirectionField.setRequired(true);
        inputLayout.add(orderByDirectionField);
        return inputLayout;
    }

    @Override
    public Map<String, Object> getQueryVariables() {
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> orderByObj = new HashMap<>();
        boolean orderByHasValues = false;
        if (orderByOrderByFieldField != null && !orderByOrderByFieldField.isEmpty()) {
            orderByObj.put("orderByField", orderByOrderByFieldField.getValue());
            orderByHasValues = true;
        }
        if (orderByDirectionField != null && !orderByDirectionField.isEmpty()) {
            orderByObj.put("direction", orderByDirectionField.getValue());
            orderByHasValues = true;
        }
        if (orderByHasValues) {
            variables.put("orderBy", orderByObj);
        }
        return variables;
    }

    @Override
    public boolean validateInputs() {
        return true;
    }
}