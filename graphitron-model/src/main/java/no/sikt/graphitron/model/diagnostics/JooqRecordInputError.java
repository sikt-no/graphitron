package no.sikt.graphitron.model.diagnostics;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the classify-phase resolution of a
 * {@code @service} parameter whose SDL input type binds a generated jOOQ {@code TableRecord} on the
 * column axis (a {@code CallSiteExtraction.JooqRecord}). Minted by {@code InputBeanResolver}, not by
 * a walker: it is a sibling sub-seal rather than an arm on {@link ServiceMethodCallError}, whose
 * javadoc scopes that seal to {@code ServiceMethodCallWalker}, so folding this in would break the
 * one-producer-per-seal scoping that seal's own sibling sub-seal note asks for.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.jooq-record-input.} namespace so the LSP {@code Diagnostic} projector reads the
 * stable wire code without a separate dispatch table.
 */
public sealed interface JooqRecordInputError extends Rejection.AuthorError permits
    JooqRecordInputError.LiveColumnCollision
{
    /** LSP wire code under the {@code graphitron.jooq-record-input.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // The typed arm keeps its structural components; prefixing is a no-op concerning structure.
        // The arm carries its own coordinate, so its message is self-sufficient without a wrap-site
        // prose prefix.
        return this;
    }

    /**
     * One of the plain {@code @field} leaves colliding on a column: its dotted access path from the
     * record's own input {@code Map} down to the leaf, and whether the leaf carries the native
     * {@code @deprecated} directive. Both halves are what the author has to act on: which fields
     * collide, and which of them are live.
     */
    record CollidingField(String path, boolean deprecated) {
        public CollidingField {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("CollidingField path must be non-empty");
            }
        }

        /** The dotted path, marked when the leaf is deprecated, for the message's field list. */
        String describe() {
            return "'" + path + "'" + (deprecated ? " (@deprecated)" : "");
        }
    }

    /**
     * Two or more <em>live</em> (non-{@code @deprecated}) plain {@code @field} leaves resolve to one
     * column of the parameter's jOOQ record. The generated helper reads one value per column, so a
     * second live writer can only silently win or lose depending on wire order, which is the
     * green-build-wrong-intent failure the acceptance axioms forbid.
     *
     * <p>The declared-alias shape is <em>not</em> this arm: when all but at most one of the colliding
     * leaves carry {@code @deprecated}, the author has said "one column, several names" and the
     * classifier merges the group into a single {@code CallSiteExtraction.ColumnBinding} with ordered
     * read paths. So the message names marking the superseded field {@code @deprecated} as a third
     * remedy alongside removing it and repointing its {@code @field(name:)}.
     *
     * <p>{@code fields} lists every plain leaf on the column (deprecated ones included, marked as
     * such) in declaration order, so the author sees the whole group rather than an arbitrary pair.
     */
    record LiveColumnCollision(
        String paramName,
        String methodName,
        String className,
        String argName,
        List<CollidingField> fields,
        String column,
        String table
    ) implements JooqRecordInputError {
        public LiveColumnCollision {
            fields = List.copyOf(fields);
            if (fields.size() < 2) {
                throw new IllegalArgumentException(
                    "LiveColumnCollision needs at least two colliding fields, got " + fields.size());
            }
        }

        @Override public String message() {
            return "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "' (GraphQL argument '" + argName + "'): input fields "
                + fields.stream().map(CollidingField::describe).collect(Collectors.joining(", "))
                + (fields.size() == 2 ? " both resolve" : " all resolve")
                + " to column '" + column + "' on table '" + table + "', and more than one of them"
                + " is live. One column takes one value per request, so the live writers would"
                + " silently last-write-wins; remove one, point its @field(name:) at a different"
                + " column, or mark the superseded field @deprecated to declare it an alias of the"
                + " one that replaced it.";
        }

        @Override public String lspCode() { return "graphitron.jooq-record-input.live-column-collision"; }
    }
}
