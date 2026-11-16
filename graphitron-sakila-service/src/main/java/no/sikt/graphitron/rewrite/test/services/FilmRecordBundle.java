package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;

import java.util.List;

/** A bean whose member is a list of jOOQ records, each built from its input object column by column. */
public record FilmRecordBundle(String name, List<FilmRecord> films) {
}
