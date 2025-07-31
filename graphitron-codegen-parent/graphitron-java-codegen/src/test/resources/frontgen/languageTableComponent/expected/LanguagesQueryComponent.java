import com.vaadin.flow.component.grid.Grid;
import fake.graphql.example.model.Language;
import fake.graphql.example.model.QueryLanguagesConnection;
import fake.graphql.example.model.QueryLanguagesConnectionEdge;
import java.lang.Class;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.function.Function;
import no.sikt.frontgen.generate.GeneratedQueryComponent;

public class LanguagesQueryComponent extends GeneratedQueryComponent<Language, QueryLanguagesConnection> {
    @Override
    public String getQuery() {
        return "query { languages(first: 100) { edges { node { id name } } } }";
    }

    @Override
    public String getRootField() {
        return "languages";
    }

    @Override
    public Class<QueryLanguagesConnection> getConnectionClass() {
        return QueryLanguagesConnection.class;
    }

    @Override
    public Function<QueryLanguagesConnection, List<?>> getEdgesFunction() {
        return QueryLanguagesConnection::getEdges;
    }

    @Override
    public Function<Object, Language> getNodeFunction() {
        return edge -> ((QueryLanguagesConnectionEdge) edge).getNode();
    }

    @Override
    public Function<List<Language>, Grid<Language>> getGridCreator() {
        return languages -> {
            Grid<Language> grid = new Grid<>(Language.class, false);
            grid.addColumn(Language::getId)
                    .setHeader("Id")
                    .setFlexGrow(1);
            grid.addColumn(Language::getName)
                    .setHeader("Name")
                    .setFlexGrow(1);
            grid.setItems(languages);
            return grid;
        };
    }

    @Override
    public String getButtonText() {
        return "List Languages";
    }
}