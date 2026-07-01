package no.sikt.graphitron.rewrite.schema.federation;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code fields:} argument of a federation {@code @key} directive.
 *
 * <p>The federation grammar is a strict subset of GraphQL selection-set syntax: a non-empty
 * whitespace-separated list of field names, optionally enclosed in braces, optionally containing
 * nested selections (which graphitron rejects, see Non-goals on the federation-via-federation-jvm
 * plan).
 *
 * <p>Accepts:
 * <ul>
 *   <li>A naked field list: {@code "id sku tenantId"}</li>
 *   <li>A braced field list: {@code "{ id sku }"}</li>
 *   <li>Standard GraphQL whitespace and line terminators between names</li>
 * </ul>
 *
 * <p>Rejects (throws {@link ParseException} with a diagnostic message; the caller maps it to a
 * {@code ValidationError} carrying the directive's source location):
 * <ul>
 *   <li>Empty / whitespace-only input</li>
 *   <li>Any character other than ASCII whitespace, {@code {}, } and standard GraphQL name
 *       characters ({@code [_A-Za-z][_0-9A-Za-z]*})</li>
 *   <li>Nested selections: any {@code {} after a name token. Diagnostic names the offending
 *       field</li>
 *   <li>Unbalanced or stray {@code {} / } </li>
 * </ul>
 *
 * <p>The federation grammar does not allow aliases, arguments, variables, dotted names,
 * hash-comments, or string/numeric values; this parser treats those as illegal characters or
 * unexpected tokens. Reusing
 * {@link no.sikt.graphitron.rewrite.selection.GraphQLSelectionParser} would force defensive
 * re-rejection of each constructs and couple us to whatever the selection parser grows in the
 * future.
 */
public final class FederationKeyFieldsParser {

    private FederationKeyFieldsParser() {}

    /**
     * Parses {@code fields} and returns the list of field names in declaration order.
     *
     * @throws ParseException with a diagnostic message when the input violates the grammar.
     *         The caller (e.g. {@code EntityResolutionBuilder}) is responsible for wrapping the
     *         message into a {@code ValidationError} with the directive's source location.
     */
    public static List<String> parse(String fields) {
        if (fields == null) {
            throw new ParseException("@key(fields:) is missing");
        }
        return new Lexer(fields).readFieldList();
    }

    /**
     * Thrown when {@code fields:} input violates the federation grammar. Carries a diagnostic
     * suitable for inclusion in a user-facing {@code ValidationError}.
     */
    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ParseException(String message) {
            super(message);
        }
    }

    private static final class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = src;
            this.pos = 0;
        }

        List<String> readFieldList() {
            skipWhitespace();
            boolean braced = false;
            if (peek() == '{') {
                braced = true;
                pos++;
                skipWhitespace();
            }
            var names = new ArrayList<String>();
            while (pos < src.length() && peek() != '}') {
                if (!isNameStart(peek())) {
                    throw new ParseException(
                        "@key(fields: " + quote(src) + "): unexpected character "
                        + describeChar(peek()) + " at position " + pos
                        + "; expected a field name");
                }
                String name = readName();
                names.add(name);
                skipWhitespace();
                if (peek() == '{') {
                    throw new ParseException(
                        "@key(fields: " + quote(src) + "): nested selections are not supported "
                        + "on this subgraph; the offending field is " + quote(name)
                        + ". Declare @key on the inner type's columns instead, or lift the "
                        + "restriction in a follow-up plan");
                }
            }
            if (braced) {
                if (pos >= src.length() || peek() != '}') {
                    throw new ParseException(
                        "@key(fields: " + quote(src) + "): unbalanced '{' — missing closing '}'");
                }
                pos++;
                skipWhitespace();
            }
            if (pos < src.length()) {
                throw new ParseException(
                    "@key(fields: " + quote(src) + "): unexpected trailing input at position "
                    + pos + " (" + describeChar(peek()) + ")");
            }
            if (names.isEmpty()) {
                throw new ParseException(
                    "@key(fields: " + quote(src) + "): empty field list; at least one field name "
                    + "is required");
            }
            return List.copyOf(names);
        }

        private String readName() {
            int start = pos;
            // Consume a standard GraphQL name: [_A-Za-z][_0-9A-Za-z]*
            pos++;
            while (pos < src.length() && isNameContinue(src.charAt(pos))) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') {
                    // Federation grammar does not officially allow commas, but graphql-java's
                    // selection grammar treats them as ignored token separators. The federation
                    // examples sometimes include them; accept here for compatibility.
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private static boolean isNameStart(char c) {
            return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        private static boolean isNameContinue(char c) {
            return isNameStart(c) || (c >= '0' && c <= '9');
        }

        private static String describeChar(char c) {
            if (c == '\0') return "end-of-input";
            if (c == ' ') return "' ' (space)";
            if (c == '\t') return "'\\t' (tab)";
            if (c == '\n') return "'\\n' (newline)";
            return "'" + c + "'";
        }

        private static String quote(String s) {
            return "\"" + s + "\"";
        }
    }
}
