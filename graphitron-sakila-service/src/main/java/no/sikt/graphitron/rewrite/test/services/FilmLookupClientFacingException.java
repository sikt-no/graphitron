package no.sikt.graphitron.rewrite.test.services;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.List;

/**
 * Fixture: the both-shapes error source. A plain {@link RuntimeException} that also implements
 * {@link GraphQLError}, which is ordinary in a graphql-java codebase and is the shape the
 * generated {@code GraphitronClientException} takes by extending
 * {@code graphql.GraphqlErrorException}.
 *
 * <p>It exists to pin a precedence decision that is otherwise untested in either direction: the
 * {@code FilmLookupClientFacing} {@code @error} type names this class from a {@code GENERIC}
 * handler carrying {@code description:}, so the emitted {@code message} fetcher can reach the
 * source through either its dispatch-table walk or its {@code GraphQLError} arm. The walk runs
 * first, so the authored description is what reaches the client; {@link #getMessage()} deliberately
 * returns something different, so a regression that resolves the {@code GraphQLError} arm first is
 * an assertion failure rather than an invisible behaviour change.
 *
 * <p>Sibling of {@link FilmLookupInvalidIdException} and {@link FilmLookupNotFoundException} on the
 * {@code FilmLookupError} union; unchecked so the service signature stays clean.
 */
public class FilmLookupClientFacingException extends RuntimeException implements GraphQLError {

    private static final long serialVersionUID = 1L;

    public FilmLookupClientFacingException(String message) {
        super(message);
    }

    @Override
    public List<SourceLocation> getLocations() {
        return List.of();
    }

    @Override
    public ErrorClassification getErrorType() {
        return ErrorType.DataFetchingException;
    }
}
