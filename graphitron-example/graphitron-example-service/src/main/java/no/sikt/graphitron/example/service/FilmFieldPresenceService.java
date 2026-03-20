package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.Film;
import no.sikt.graphitron.example.service.records.FilmFieldPresence;
import no.sikt.graphitron.example.service.records.FilmInsertWrapper;
import org.jooq.DSLContext;

import java.util.List;

public class FilmFieldPresenceService {

    public FilmFieldPresenceService(DSLContext ctx) {
    }

    public List<FilmFieldPresence> check(List<FilmInsertWrapper> input) {
        return input.stream()
                .flatMap(wrapper -> wrapper.getFilms().stream())
                .map(film -> new FilmFieldPresence(
                        film.getTitle(),
                        film.changed(Film.FILM.RENTAL_DURATION)
                ))
                .toList();
    }
}