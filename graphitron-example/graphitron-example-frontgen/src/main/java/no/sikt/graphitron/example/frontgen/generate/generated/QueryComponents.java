package no.sikt.graphitron.example.frontgen.generate.generated;

import no.sikt.graphitron.example.frontgen.components.QueryBackedView;
import no.sikt.graphitron.example.frontgen.components.QueryComponent;

public class QueryComponents {

    /**
     * Creates a film query component
     */
    public static QueryComponent createFilmComponent(QueryBackedView view) {
        return new FilmQueryComponent().createComponent(view);
    }

    /**
     * Creates a customer query component
     */
    public static QueryComponent createCustomerComponent(QueryBackedView view) {
        return new CustomerQueryComponent().createComponent(view);
    }
}