package no.sikt.graphitron.example.temp.generated;

import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;

import java.util.List;

public class QueryComponents {

    public static List<QueryComponent> getComponents(QueryBackedView view) {
        return List.of(
                new FilmQueryComponent().createComponent(view),
                new CustomerQueryComponent().createComponent(view),
                new LanguageQueryComponent().createComponent(view)
        );
    }
}