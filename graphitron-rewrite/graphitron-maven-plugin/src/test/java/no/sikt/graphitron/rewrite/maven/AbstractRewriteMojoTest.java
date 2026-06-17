package no.sikt.graphitron.rewrite.maven;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the one-shot mojo's rendering of a {@code ValidationFailedException}: the failure message
 * must carry the per-error {@code file:line:col} detail, not just the bare count, and it must read
 * identically to what {@code DevMojo} renders for the same errors (parity is structural: both route
 * through {@link WatchErrorFormatter#format}).
 */
class AbstractRewriteMojoTest {

    @Test
    void validationFailureMessage_rendersPerErrorFileLineColDetail() {
        var errors = List.of(
            error("Query.foo", "Field 'Query.foo': missing @reference target",
                "src/main/resources/schema/query.graphqls", 12, 3),
            error("User.orders", "Field 'User.orders': not on a table-backed type",
                "src/main/resources/schema/user.graphqls", 42, 5));

        String msg = AbstractRewriteMojo.validationFailureMessage(errors);

        assertThat(msg)
            .contains("GraphQL schema validation failed:")
            // The source file and line:col coordinate of each error surfaces, not just a count.
            .contains("src/main/resources/schema/query.graphqls")
            .contains("12:3")
            .contains("Field 'Query.foo': missing @reference target")
            .contains("src/main/resources/schema/user.graphqls")
            .contains("42:5")
            .contains("Field 'User.orders': not on a table-backed type")
            // The kind summary line is present; the message is more than the bare count.
            .contains("2 error(s):");
    }

    @Test
    void validationFailureMessage_matchesDevLoopRenderer() {
        var errors = List.of(
            error("Query.foo", "Field 'Query.foo': missing @reference target",
                "src/main/resources/schema/query.graphqls", 12, 3));

        String oneShot = AbstractRewriteMojo.validationFailureMessage(errors);

        // Parity with DevMojo: the one-shot failure embeds the exact tree the dev loop renders for
        // the same errors (null previous-key set => no delta line), so the two surfaces cannot drift.
        assertThat(oneShot).contains(WatchErrorFormatter.format(errors, null));
    }

    private static ValidationError error(String coordinate, String message, String file, int line, int col) {
        return new ValidationError(coordinate, Rejection.invalidSchema(message),
            new SourceLocation(line, col, file));
    }
}
