package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * One cell of a column pairing between two tables: the column on the side a join departs from and
 * the column on the side it lands on, paired once where the row is minted so no reader re-answers
 * which end is which.
 *
 * <p>Direction is baked in and readers are direction-blind, which is the whole reason the pairing
 * is a list of pairs rather than two parallel lists with an equal-length precondition. Which end
 * of a foreign key sits on which table is a question about the catalog, answered once by whoever
 * resolved the join; a pair that arrives here has already been oriented.
 *
 * <p>The source side is also what a write captures. A routine write projects its result rows'
 * source-side columns inside the transaction and filters the post-commit re-read on the target
 * side, so one pairing states both halves of that correlation and nothing has to agree with
 * anything by position.
 */
public record KeyPair(CatalogColumn sourceSide, CatalogColumn targetSide) {

    public KeyPair {
        Objects.requireNonNull(sourceSide, "sourceSide");
        Objects.requireNonNull(targetSide, "targetSide");
    }
}
