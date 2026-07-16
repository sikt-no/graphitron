package no.sikt.graphitron.lsp.parsing;

/**
 * Identifies a unique element of the GraphQL directive schema. The four
 * cases cover everything the LSP's vocabulary needs to key against today:
 * a directive itself ({@link Directive}), one of its arguments
 * ({@link DirectiveArg}), an input type referenced from such an argument
 * ({@link InputType}), and a field on such an input type ({@link InputField}).
 *
 * <p>String forms in {@code toString()} follow the GraphQL spec's
 * schema-coordinate syntax, suitable for log and error messages:
 * <ul>
 *   <li>{@code @service}</li>
 *   <li>{@code @service(service:)}</li>
 *   <li>{@code ExternalCodeReference}</li>
 *   <li>{@code ExternalCodeReference.className}</li>
 * </ul>
 *
 * <p>Type / field coordinates beyond the directive surface
 * (e.g. {@code Query.user(id:)}) are not in scope today; graphitron's LSP
 * does not validate against user-authored types. The sealed hierarchy
 * admits a future {@code FieldCoordinate} arm without disturbing existing
 * consumers.
 */
public sealed interface SchemaCoordinate {

    /** A top-level directive, e.g. {@code @service}. */
    record Directive(String name) implements SchemaCoordinate {
        @Override
        public String toString() {
            return "@" + name;
        }
    }

    /** A directive argument, e.g. {@code @service(service:)}. */
    record DirectiveArg(String directive, String arg) implements SchemaCoordinate {
        @Override
        public String toString() {
            return "@" + directive + "(" + arg + ":)";
        }
    }

    /** An input type, e.g. {@code ExternalCodeReference}. */
    record InputType(String name) implements SchemaCoordinate {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A field on an input type, e.g. {@code ExternalCodeReference.className}. */
    record InputField(String type, String field) implements SchemaCoordinate {
        @Override
        public String toString() {
            return type + "." + field;
        }
    }
}
