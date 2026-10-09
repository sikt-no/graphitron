package no.sikt.graphitron.rewrite.test.backing;

import java.util.List;

/**
 * The head of the chain. Its {@code related} member closes a cycle, and its {@code reviews},
 * {@code rating} and {@code score} members deliver classes that a field carrying a producer of its
 * own must not be backed by.
 */
public record Film(
    String title,
    Language language,
    List<Actor> actors,
    List<WrongReview> reviews,
    WrongRating rating,
    WrongScore score,
    Film related,
    GroundedRecord grounded
) {}
