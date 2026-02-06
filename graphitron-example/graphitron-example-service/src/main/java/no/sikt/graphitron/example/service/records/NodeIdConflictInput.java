package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.FilmActorRecord;

/**
 * Test Java record (POJO) for @nodeId conflict testing.
 * Both filmActorId and actorId @nodeId fields map to the 'filmActor' field.
 * FilmActor has composite key (ACTOR_ID, FILM_ID), and Actor has key ACTOR_ID.
 * If they encode different actor IDs, a conflict should be detected at runtime.
 */
public class NodeIdConflictInput {
    private String name;
    private FilmActorRecord filmActor;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FilmActorRecord getFilmActor() {
        return filmActor;
    }

    public void setFilmActor(FilmActorRecord filmActor) {
        this.filmActor = filmActor;
    }
}