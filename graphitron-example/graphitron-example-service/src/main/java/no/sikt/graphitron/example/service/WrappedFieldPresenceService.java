package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.service.records.WrappedFieldInput;
import no.sikt.graphitron.example.service.records.WrappedFieldResult;
import org.jooq.DSLContext;

import java.util.List;

public class WrappedFieldPresenceService {

    public WrappedFieldPresenceService(DSLContext ctx) {
    }

    public List<WrappedFieldResult> check(List<WrappedFieldInput> input) {
        return input.stream()
                .map(film -> new WrappedFieldResult(
                        film.getTitle(),
                        film.getDescription(),
                        film.getRentalDuration()
                ))
                .toList();
    }
}
