/**
 * A small class graph for which class backs a type: a chain three types deep ({@link Film},
 * {@link Language}, {@link Country}), a cycle ({@link Film#related()}), a type two producers answer
 * differently ({@link Left}, {@link Right}), an input with a nested input ({@link FilmFilter},
 * {@link NestedFilter}), and members whose classes no field should be backed by, because the field
 * reading them carries a producer of its own ({@link WrongReview}, {@link WrongRating},
 * {@link WrongScore}). None of it touches a database; the methods exist to be read.
 */
package no.sikt.graphitron.rewrite.test.backing;
