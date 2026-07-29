package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The pagination fact relation: one row per reachable field coordinate that carries any of the
 * four reserved pagination argument names (the authored population) or the pagination-implying
 * connection directive (the inferred population, with its coerced authored default page size).
 * Both populations are produced once, here, by {@link PaginationFactVisitor}; every reader
 * (the argument classifier's pagination arm, the pagination-spec view, the connection carrier
 * rewrite's injected default) reads a resolved view over these rows, and none re-reads the
 * directive.
 *
 * <p>Rows are labeled with their coordinate but indexed by field-definition node identity
 * within the one pre-rewrite assembled schema the gather walked: every reader holds the same
 * definition node the traversal visited, and the connection rewrite (which transforms
 * definitions) runs strictly downstream of both the gather and every read. The per-fact
 * population pin is the drift enforcer for that keying.
 */
public record PaginationFacts(Map<GraphQLFieldDefinition, Row> rowsByDefinition) {

    public PaginationFacts {
        Objects.requireNonNull(rowsByDefinition, "rowsByDefinition");
    }

    /** The four reserved pagination argument roles; the SDL argument name is fixed per role. */
    public enum Role {
        FIRST, LAST, AFTER, BEFORE;

        /** The reserved SDL argument name carrying this role. */
        public String argName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One authored pagination argument on the coordinate: its role and the argument's declared
     * shape, read off the SDL at gather time.
     */
    public record PaginationArg(Role role, String typeName, boolean nonNull, boolean list) {
        public PaginationArg {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(typeName, "typeName");
        }
    }

    /**
     * One coordinate's pagination facts. {@code args} is the authored population in SDL
     * declaration order; {@code asConnection} is the inferred population's presence flag;
     * {@code authoredDefaultFirst} is the directive's coerced default-page-size argument when
     * the author wrote one (the fallback constant is the resolved view's concern, not a fact).
     * A row exists exactly when at least one population is non-empty.
     */
    public record Row(String parentTypeName, String fieldName, List<PaginationArg> args,
                      boolean asConnection, OptionalInt authoredDefaultFirst) {
        public Row {
            Objects.requireNonNull(parentTypeName, "parentTypeName");
            Objects.requireNonNull(fieldName, "fieldName");
            args = List.copyOf(args);
            Objects.requireNonNull(authoredDefaultFirst, "authoredDefaultFirst");
            if (args.isEmpty() && !asConnection) {
                throw new IllegalArgumentException(
                    "a pagination row exists only when a population is non-empty; coordinate "
                    + parentTypeName + "." + fieldName + " has neither pagination args nor the"
                    + " connection directive");
            }
        }
    }

    /** The coordinate's row, or empty when neither population touches it. */
    public Optional<Row> rowFor(GraphQLFieldDefinition fieldDef) {
        return Optional.ofNullable(rowsByDefinition.get(fieldDef));
    }

    /** The authored pagination argument named {@code argName} on the coordinate, if any. */
    public Optional<PaginationArg> argFor(GraphQLFieldDefinition fieldDef, String argName) {
        var row = rowsByDefinition.get(fieldDef);
        if (row == null) {
            return Optional.empty();
        }
        return row.args().stream().filter(a -> a.role().argName().equals(argName)).findFirst();
    }

    /** Every row, for the population pins. */
    public Collection<Row> rows() {
        return rowsByDefinition.values();
    }
}
