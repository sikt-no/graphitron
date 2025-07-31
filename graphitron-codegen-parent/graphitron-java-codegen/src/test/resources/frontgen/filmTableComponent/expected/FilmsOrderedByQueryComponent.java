import com.vaadin.flow.component.grid.Grid;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.QueryFilmsConnection;
import fake.graphql.example.model.QueryFilmsConnectionEdge;
import java.lang.Class;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.function.Function;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

public class FilmsOrderedByQueryComponent extends GeneratedQueryComponent<Film, QueryFilmsConnection> {
    @Override
    public String getQuery() {
        return "query { filmsOrderedBy(first: 100) { edges { node { id title } } } }";
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
}
