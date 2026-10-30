package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture payload for a child {@code @service} on the error channel: the value
 * {@link FilmTitleCheckService#check} returns per film, with an {@code errors} field the child
 * fetcher's catch arm fills when the batch throws. Graphitron never constructs this record.
 */
public record FilmTitleCheckPayload(String title, List<?> errors) {}
