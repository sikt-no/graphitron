package no.sikt.graphitron.example.frontgen.generate.generated;

import no.sikt.graphitron.example.frontgen.components.QueryBackedView;
import no.sikt.graphitron.example.frontgen.components.QueryComponent;

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