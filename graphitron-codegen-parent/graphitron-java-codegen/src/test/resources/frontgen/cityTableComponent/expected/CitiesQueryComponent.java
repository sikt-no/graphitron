import com.vaadin.flow.component.grid.Grid;
import fake.graphql.example.model.City;
import fake.graphql.example.model.QueryCitiesConnection;
import fake.graphql.example.model.QueryCitiesConnectionEdge;
import java.lang.Class;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.function.Function;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

public class CitiesQueryComponent extends GeneratedQueryComponent<City, QueryCitiesConnection> {
    @Override
    public String getQuery() {
        return "query { cities(first: 100) { edges { node { id name countryName payments {  amount dateTime } } } } }";
    }

    @Override
    public String getRootField() {
        return "cities";
    }

    @Override
    public Class<QueryCitiesConnection> getConnectionClass() {
        return QueryCitiesConnection.class;
    }

    @Override
    public Function<QueryCitiesConnection, List<?>> getEdgesFunction() {
        return QueryCitiesConnection::getEdges;
    }

    @Override
    public Function<Object, City> getNodeFunction() {
        return edge -> ((QueryCitiesConnectionEdge) edge).getNode();
    }

    @Override
    public Function<List<City>, Grid<City>> getGridCreator() {
        return citys -> {
            Grid<City> grid = new Grid<>(City.class, false);
            grid.addColumn(City::getId)
                        .setHeader("Id")
                        .setFlexGrow(1);
            grid.addColumn(City::getName)
                        .setHeader("Name")
                        .setFlexGrow(1);
            grid.addColumn(City::getCountryName)
                        .setHeader("Country Name")
                        .setFlexGrow(1);
            grid.setItems(citys);
            return grid;
        };
    }

    @Override
    public String getButtonText() {
        return "List Cities";
    }
}
