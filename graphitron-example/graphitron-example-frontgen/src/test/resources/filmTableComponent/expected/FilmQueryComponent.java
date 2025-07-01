import com.vaadin.flow.component.grid.Grid;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.function.Function;
import no.sikt.graphitron.example.frontgen.generate.GeneratedQueryComponent;
import no.sikt.graphitron.example.generated.graphitron.model.Film;
import no.sikt.graphitron.example.generated.graphitron.model.QueryFilmsConnection;
import no.sikt.graphitron.example.generated.graphitron.model.QueryFilmsConnectionEdge;

public class FilmQueryComponent extends GeneratedQueryComponent<Film, QueryFilmsConnection> {
    @Override
    protected String getQuery() {
        return "query { films(first: 100) { edges { node { id title } } } }";
    }

    @Override
    protected String getRootField() {
        return "films";
    }

    @Override
    protected Function<QueryFilmsConnection, List> getEdgesFunction() {
        return QueryFilmsConnection::getEdges;
    }

    @Override
    protected Function<Object, Film> getNodeFunction() {
        return edge -> ((QueryFilmsConnectionEdge) edge).getNode();
    }

    @Override
    protected Function<List<Film>, Grid<Film>> getGridCreator() {
        return films -> {
            Grid<Film> grid = new Grid<>(Film.class, false);
            grid.addColumn(Film::getId)
                    .setHeader("ID")
                    .setFlexGrow(1);
            grid.addColumn(Film::getTitle)
                    .setHeader("Title")
                    .setFlexGrow(2);
            grid.setItems(films);
            return grid;
        };
    }

    @Override
    protected String getButtonText() {
        return "List Films";
    }
}
