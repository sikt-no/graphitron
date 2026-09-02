package no.sikt.graphitron.model.schema.federation;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code fields:} argument of a federation {@code @key} directive.
 *
 * <p>The federation grammar is a strict subset of GraphQL selection-set syntax: a non-empty
 * whitespace-separated list of field names, optionally enclosed in braces. Nested selections,
 * aliases, arguments, variables, comments, and non-name values are rejected with a
 * {@link ParseException}; the caller maps its message to a {@code ValidationError} carrying the
 * directive's source location. Reusing
 * {@link no.sikt.graphitron.model.selection.GraphQLSelectionParser} would force defensive
 * re-rejection of each of those constructs and couple this grammar to whatever the selection
 * parser accepts.
 */
public final class FederationKeyFieldsParser {

    private FederationKeyFieldsParser() {}

    /**
     * Parses {@code fields} and returns the field names in declaration order.
     *
     * @throws ParseException when the input violates the grammar; the caller (e.g.
     *         {@code EntityResolutionBuilder}) wraps the message into a {@code ValidationError}
     *         with the directive's source location.
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
                    // The federation grammar has no commas, but graphql-java treats them as
                    // ignored separators and federation examples include them; accept for
                    // compatibility.
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
