package no.sikt.graphitron.emit.stub;

import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;

/** The generated error router: claims what a body throws into an outcome, or redacts it. */
public final class ErrorRouter {

    private ErrorRouter() {}

    public static <P> DataFetcherResult<P> surfaceClientErrorOrRedact(Throwable thrown, DataFetchingEnvironment env) {
        return DataFetcherResult.<P>newResult()
            .error(GraphqlErrorBuilder.newError().message("redacted: " + thrown.getMessage()).build())
            .build();
    }

    /** Claims a throw whose message is {@code refused}, under the first mapping; nothing else. */
    public static Throwable claim(Throwable thrown, String[] mappings, DataFetchingEnvironment env) {
        return "refused".equals(thrown.getMessage()) ? new Claimed(mappings[0] + " " + thrown.getMessage()) : null;
    }

    /** What a claim makes of a throw, printed as its message. */
    public static final class Claimed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Claimed(String message) {
            super(message);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }
}
