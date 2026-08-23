package no.sikt.graphitron.command;

import java.util.List;
import java.util.Objects;

/**
 * A call to a database routine, as data: the generated convenience method to invoke, the result
 * table its rows arrive as, and the arguments to pass, in the order the routine declares its
 * parameters.
 *
 * <p>jOOQ generates a table-valued function as a first-class catalog table plus a method on the
 * schema's {@code Routines} class returning it, so a call has a table identity as well as an
 * invocation, and both ride here: {@link #resultTable} is what a local holding the call is declared
 * as and what a capture reads its columns off, while {@link #routinesClassName} and
 * {@link #methodName} are the invocation itself.
 *
 * <p>Positional by construction, because the emitted call is. {@link #arguments} is spelled into
 * the method's parameter list in this order and nothing at the call site names a parameter, so the
 * order is the whole binding; each argument carries its parameter's name anyway, which is what
 * makes the order checkable by something other than reading the generated source.
 *
 * @param routinesClassName the generated {@code Routines} class's fully qualified name
 * @param methodName        the table-form convenience method on it
 * @param resultTable       the catalog table the function's result is generated as
 * @param arguments         the routine's IN parameters in declaration order
 */
public record RoutineCall(String routinesClassName, String methodName, CatalogTable resultTable,
                          List<RoutineArgument> arguments) {

    public RoutineCall {
        Objects.requireNonNull(routinesClassName, "routinesClassName");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(resultTable, "resultTable");
        arguments = List.copyOf(arguments);
        if (routinesClassName.isBlank() || methodName.isBlank()) {
            throw new IllegalArgumentException(
                "a routine call names the generated routines class and its method; a blank one"
                + " would emit as an unparseable call rather than failing here");
        }
    }

    /**
     * One IN parameter of the call, bound to the request value that supplies it.
     *
     * <p>One arm and not two, which is a narrowing this family pays for rather than a gap. A
     * routine parameter can also read a column of the node a chain arrives from, and does wherever
     * a routine is written as a chain's non-head node; a routine <em>write</em> is a mutation
     * root's, where the routine is the chain's head and there is no previous node for a column
     * binding to name. The classifier refuses such a binding at a root, so a second arm here would
     * be a shape this vocabulary can spell and no producer can mint.
     *
     * @param parameterName the routine's own name for the parameter, which the emission does not
     *                      spell: the call is positional, and this is what lets a reader check the
     *                      order against the catalog rather than against the generated source
     * @param javaTypeName  the parameter's Java type as jOOQ binds it, written the way source
     *                      writes it: {@code java.lang.Integer}, and {@code java.lang.Integer[]}
     *                      for an array parameter. The source form and not the reflected
     *                      descriptor a {@link CatalogColumn} carries, because this name is spelled
     *                      into a type argument at the call site where the column's is spelled into
     *                      a record type; a producer holding the reflected form normalises it where
     *                      the row is minted, which is where a spelling difference belongs
     * @param path          where the value is read from the request: the author's
     *                      {@code argMapping} right-hand side as written, dot-separated, or the
     *                      parameter's own name where the author wrote no entry and the call
     *                      identity-binds the argument of that name. The written form and not a
     *                      resolved path carrier, matching what {@link KeyProjectionRelation} keys
     *                      its rows by and what
     *                      {@link no.sikt.graphitron.rewrite.PathExpr#asString()} renders. The
     *                      per-segment list-lifting a resolved path also carries decides nothing at
     *                      this call site: the emission reads segment names and nothing else
     */
    public record RoutineArgument(String parameterName, String javaTypeName, String path) {

        public RoutineArgument {
            Objects.requireNonNull(parameterName, "parameterName");
            Objects.requireNonNull(javaTypeName, "javaTypeName");
            Objects.requireNonNull(path, "path");
            if (javaTypeName.isBlank() || path.isBlank()) {
                throw new IllegalArgumentException(
                    "a routine argument carries its parameter's bound type and the path its value"
                    + " is read from; the read at the call site is typed by the one and addressed"
                    + " by the other, so a blank either way emits a call that does not compile");
            }
        }

        /** The path's segments, outermost first; a bare slot is one segment. */
        public List<String> segments() {
            return List.of(path.split("\\."));
        }
    }
}
