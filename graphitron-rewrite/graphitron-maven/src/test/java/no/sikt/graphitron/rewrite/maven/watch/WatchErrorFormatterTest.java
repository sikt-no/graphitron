package no.sikt.graphitron.rewrite.maven.watch;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WatchErrorFormatterTest {

    @Test
    void emptyInput_rendersClearMessage() {
        assertThat(WatchErrorFormatter.format(List.of(), null))
            .isEqualTo("graphitron:watch: no validation errors");
    }

    @Test
    void groupsByFileThenTypeThenField() {
        var errors = List.of(
            error(RejectionKind.AUTHOR_ERROR, "User.orders",
                "Field 'User.orders': missing @reference target", "schema/user.graphqls", 42, 5),
            error(RejectionKind.DEFERRED, "User.orders",
                "Field 'User.orders': feature deferred", "schema/user.graphqls", 42, 5),
            error(RejectionKind.AUTHOR_ERROR, "User.profile",
                "Field 'User.profile': bad config", "schema/user.graphqls", 55, 5),
            error(RejectionKind.AUTHOR_ERROR, "User",
                "Type 'User': missing @table target", "schema/user.graphqls", 30, 1),
            error(RejectionKind.INVALID_SCHEMA, "Order.total",
                "Field 'Order.total': not on a table-backed type", "schema/order.graphqls", 12, 3)
        );

        String out = WatchErrorFormatter.format(errors, null);

        // Files render in alphabetical order; Order before User.
        int orderHeader = out.indexOf("schema/order.graphqls");
        int userHeader = out.indexOf("schema/user.graphqls");
        assertThat(orderHeader).isGreaterThanOrEqualTo(0).isLessThan(userHeader);

        // Per-file count is right.
        assertThat(out).contains("schema/user.graphqls (4 errors)");
        assertThat(out).contains("schema/order.graphqls (1 error)");

        // Type-level error precedes field subgroups within a type.
        int typeUser = out.indexOf("type User", userHeader);
        int typeLine = out.indexOf("30:1", typeUser);
        int firstField = out.indexOf("User.orders", typeUser);
        assertThat(typeUser).isLessThan(typeLine);
        assertThat(typeLine).isLessThan(firstField);

        // The two errors on User.orders are collapsed under one heading with a count, in line order.
        assertThat(out).contains("User.orders  (2 errors)");

        // Kind summary appears at the end with both kinds counted.
        assertThat(out).contains("5 error(s):").contains("3 author-error").contains("1 deferred")
            .contains("1 invalid-schema");
    }

    @Test
    void schemaLevelErrorsRenderInOwnBlock() {
        var errors = List.of(
            // null coordinate => schema-level
            new ValidationError(RejectionKind.AUTHOR_ERROR, null,
                "schema-wide: duplicate type Order across two files",
                new SourceLocation(1, 1, "schema/main.graphqls")),
            error(RejectionKind.AUTHOR_ERROR, "User.orders",
                "Field 'User.orders': bad", "schema/main.graphqls", 5, 1)
        );

        String out = WatchErrorFormatter.format(errors, null);
        assertThat(out).contains("schema:")
            .contains("duplicate type Order across two files");
        // Schema block precedes type block.
        assertThat(out.indexOf("schema:")).isLessThan(out.indexOf("type User"));
    }

    @Test
    void deltaLine_reportsAddedFixedAndUnchanged() {
        var prevErrors = List.of(
            error(RejectionKind.AUTHOR_ERROR, "User.orders",
                "Field 'User.orders': bad ref", "schema/user.graphqls", 42, 5),
            error(RejectionKind.AUTHOR_ERROR, "User.profile",
                "Field 'User.profile': bad config", "schema/user.graphqls", 55, 5)
        );
        Set<WatchErrorFormatter.DeltaKey> previous = WatchErrorFormatter.keysOf(prevErrors);

        // User.orders moved (line shift) but key is line-independent: counts as unchanged.
        // User.profile fixed (gone).
        // New error on Order.total.
        var current = List.of(
            error(RejectionKind.AUTHOR_ERROR, "User.orders",
                "Field 'User.orders': bad ref", "schema/user.graphqls", 99, 5),
            error(RejectionKind.INVALID_SCHEMA, "Order.total",
                "Field 'Order.total': not on a table-backed type", "schema/order.graphqls", 12, 3)
        );

        String out = WatchErrorFormatter.format(current, previous);
        assertThat(out).contains("+1 new, -1 fixed, 1 unchanged");
    }

    @Test
    void noPreviousCycle_omitsDelta() {
        var errors = List.of(
            error(RejectionKind.AUTHOR_ERROR, "User.orders",
                "Field 'User.orders': bad", "schema/user.graphqls", 1, 1));
        String out = WatchErrorFormatter.format(errors, null);
        assertThat(out).doesNotContain("new")
            .doesNotContain("fixed")
            .doesNotContain("unchanged");
    }

    @Test
    void deltaKeyIsLineIndependent() {
        var a = error(RejectionKind.AUTHOR_ERROR, "User.orders",
            "msg", "schema/user.graphqls", 10, 1);
        var b = error(RejectionKind.AUTHOR_ERROR, "User.orders",
            "msg", "schema/user.graphqls", 99, 4);
        assertThat(WatchErrorFormatter.DeltaKey.of(a))
            .isEqualTo(WatchErrorFormatter.DeltaKey.of(b));
    }

    private static ValidationError error(RejectionKind kind, String coord, String message,
                                         String file, int line, int col) {
        return new ValidationError(kind, coord, message, new SourceLocation(line, col, file));
    }
}
