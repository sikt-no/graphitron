package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmActorRecord;

/**
 * Test Java record (POJO) with a jOOQ record field that has composite primary keys.
 * Used to test transformation of @nodeId fields to jOOQ records when the node type
 * has composite keys (ACTOR_ID, FILM_ID in FILM_ACTOR table).
 */
public class CompositeKeyInputJavaRecord {
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
