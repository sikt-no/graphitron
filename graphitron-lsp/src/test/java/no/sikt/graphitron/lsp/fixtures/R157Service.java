package no.sikt.graphitron.lsp.fixtures;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;

/**
 * Test producer for the classifier pipeline test, which is in graphitron-maven-plugin because
 * its subject is the classifier and the editor agreeing. Reflection-only record binding means a type
 * acquires its backing class by being the reflected return type of a producer field; the
 * deprecated {@code @record} directive no longer binds. These methods let the test SDL bind
 * {@code FilmCard} / {@code FilmPojoView} to {@link R157FilmRecord} / {@link R157FilmPojo} through
 * a real {@code @service} producer. Bodies never run, only the declared return type is reflected.
 */
public final class R157Service {

    private R157Service() {}

    public static R157FilmRecord makeFilmRecord() {
        throw new UnsupportedOperationException("codegen-time return-type stub");
    }

    public static R157FilmPojo makeFilmPojo() {
        throw new UnsupportedOperationException("codegen-time return-type stub");
    }

    /**
     * Grounds a type on {@link FilmCardRecord}, whose {@code detail} component then carries an
     * accessor hop onto {@link R157FilmPojo}. The pair is what a test needs to put a hop and a
     * grounding on one SDL type.
     */
    public static FilmCardRecord makeFilmCard() {
        throw new UnsupportedOperationException("codegen-time return-type stub");
    }

    /**
     * Grounds a type on the generated jOOQ record for {@code film}, which is the case where the
     * class standing for a type is a table's row type and the type therefore resolves against the
     * table's columns rather than the class's members. The catalog census is the only thing that
     * names this class: the classpath scan excludes the generated package by design.
     */
    public static FilmRecord makeFilmRow() {
        throw new UnsupportedOperationException("codegen-time return-type stub");
    }
}
