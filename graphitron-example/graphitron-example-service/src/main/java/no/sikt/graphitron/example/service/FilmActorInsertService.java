package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.service.records.FilmActorInsertResult;
import no.sikt.graphitron.example.service.records.FilmActorInsertWrapper;
import org.jooq.DSLContext;

public class FilmActorInsertService {
    private final DSLContext ctx;

    public FilmActorInsertService(DSLContext ctx) {
        this.ctx = ctx;
    }

    public FilmActorInsertResult insert(FilmActorInsertWrapper wrapper) {
        int count = 0;
        for (var record : wrapper.getFilmActors()) {
            record.attach(ctx.configuration());
            count += record.insert();
        }
        return new FilmActorInsertResult(count);
    }
}
