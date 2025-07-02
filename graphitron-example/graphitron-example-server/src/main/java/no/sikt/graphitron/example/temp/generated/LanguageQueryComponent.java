package no.sikt.graphitron.example.temp.generated;

import com.vaadin.flow.component.grid.Grid;
import no.sikt.graphitron.example.frontgen.generate.GeneratedQueryComponent;
import no.sikt.graphitron.example.generated.graphitron.model.Language;
import no.sikt.graphitron.example.generated.graphitron.model.QueryLanguagesConnection;
import no.sikt.graphitron.example.generated.graphitron.model.QueryLanguagesConnectionEdge;

import java.util.List;
import java.util.function.Function;

public class LanguageQueryComponent extends GeneratedQueryComponent<Language, QueryLanguagesConnection> {
    @Override
    protected String getQuery() {
        return "query { languages(first: 100) { edges { node { id } } } }";
    }

    @Override
    protected String getRootField() {
        return "languages";
    }

    @Override
    protected Class<QueryLanguagesConnection> getConnectionClass() {
        return QueryLanguagesConnection.class;
    }

    @Override
    protected Function<QueryLanguagesConnection, List<?>> getEdgesFunction() {
        return QueryLanguagesConnection::getEdges;
    }

    @Override
    protected Function<Object, Language> getNodeFunction() {
        return edge -> ((QueryLanguagesConnectionEdge) edge).getNode();
    }

    @Override
    protected Function<List<Language>, Grid<Language>> getGridCreator() {
        return languages -> {
            Grid<Language> grid = new Grid<>(Language.class, false);
            grid.addColumn(Language::getId)
                    .setHeader("ID")
                    .setFlexGrow(1);
            grid.setItems(languages);
            return grid;
        };
    }

    @Override
    protected String getButtonText() {
        return "List Languages";
    }
}