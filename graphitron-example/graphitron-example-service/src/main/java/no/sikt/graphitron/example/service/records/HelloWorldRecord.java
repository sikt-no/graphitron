package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmRecord;

import java.util.List;

public class HelloWorldRecord {
    private String greeting;
    private FilmRecord film;
    private List<FilmRecord> films;
    private CustomerRecord customer;

    public List<FilmRecord> getFilms() {
        return films;
    }

    public void setFilms(List<FilmRecord> films) {
        this.films = films;
    }

    public String getGreeting() {
        return greeting;
    }

    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }

    public FilmRecord getFilm() {
        return film;
    }

    public void setFilm(FilmRecord film) {
        this.film = film;
    }

    public CustomerRecord getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerRecord customer) {
        this.customer = customer;
    }
}
