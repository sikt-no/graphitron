package no.sikt.graphql.selection;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for the extended GraphQL selection-set syntax.
 *
 * <p>Extensions over the standard grammar:
 * <ul>
 *   <li>Field names may contain dots ({@code .}), e.g. {@code some.dotted.field}.</li>
 *   <li>Commas are treated as insignificant whitespace (same as standard GraphQL).</li>
 * </ul>
 */
class Lexer {

    private final String input;
    private int pos;
    private final List<Token> tokens;
    private int tokenIndex;

    Lexer(String input) {
        this.input = input;
        this.pos = 0;
        this.tokens = tokenize();
        this.tokenIndex = 0;
    }

    Token peek() {
        return tokenIndex < tokens.size() ? tokens.get(tokenIndex) : Token.EOF;
    }

    Token next() {
        Token t = peek();
        if (tokenIndex < tokens.size()) {
            tokenIndex++;
        }
        return t;
    }

    // ---------------------------------------------------------------------------
    // Tokenization
    // ---------------------------------------------------------------------------

    private List<Token> tokenize() {
        List<Token> result = new ArrayList<>();
        while (pos < input.length()) {
            skipInsignificant();
            if (pos >= input.length()) {
                break;
            }
            result.add(readToken());
        }
        return result;
    }

    /** Skip whitespace, commas (insignificant in GraphQL) and line comments. */
    private void skipInsignificant() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF' || c == ',') {
                pos++;
            } else if (c == '#') {
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    private Token readToken() {
        char c = input.charAt(pos);
        return switch (c) {
            case '{' -> { pos++; yield new Token(TokenKind.LBRACE, "{"); }
            case '}' -> { pos++; yield new Token(TokenKind.RBRACE, "}"); }
            case '(' -> { pos++; yield new Token(TokenKind.LPAREN, "("); }
            case ')' -> { pos++; yield new Token(TokenKind.RPAREN, ")"); }
            case '[' -> { pos++; yield new Token(TokenKind.LBRACKET, "["); }
            case ']' -> { pos++; yield new Token(TokenKind.RBRACKET, "]"); }
            case ':' -> { pos++; yield new Token(TokenKind.COLON, ":"); }
            case '$' -> readVariable();
            case '"' -> readString();
            default -> {
                if (c == '-' || Character.isDigit(c)) {
                    yield readNumber();
                } else if (c == '_' || Character.isLetter(c)) {
                    yield readName();
                } else {
                    throw new GraphQLSelectionParseException(
                            "Unexpected character '" + c + "' at position " + pos);
                }
            }
        };
    }

    /**
     * Reads a name token.  Unlike standard GraphQL, dots are allowed inside names
     * to support {@code some.dotted.field} syntax.
     *
     * <p>Grammar (extended):
     * <pre>Name ::= [_A-Za-z][_0-9A-Za-z.]*</pre>
     */
    private Token readName() {
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '_' || Character.isLetterOrDigit(c) || c == '.') {
                pos++;
            } else {
                break;
            }
        }
        return new Token(TokenKind.NAME, input.substring(start, pos));
    }

    /** Reads a double-quoted string with basic escape sequences. */
    private Token readString() {
        pos++; // opening "
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++;
                return new Token(TokenKind.STRING, sb.toString());
            } else if (c == '\\') {
                pos++;
                if (pos >= input.length()) {
                    break;
                }
                char esc = input.charAt(pos++);
                sb.append(switch (esc) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> throw new GraphQLSelectionParseException(
                            "Unknown string escape: \\" + esc);
                });
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new GraphQLSelectionParseException("Unterminated string literal");
    }

    /** Reads an integer or float literal. */
    private Token readNumber() {
        int start = pos;
        if (input.charAt(pos) == '-') {
            pos++;
        }
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        boolean isFloat = false;
        if (pos < input.length() && input.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        return new Token(isFloat ? TokenKind.FLOAT : TokenKind.INT, input.substring(start, pos));
    }

    /** Reads a variable reference ({@code $name}). */
    private Token readVariable() {
        pos++; // skip $
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '_' || Character.isLetterOrDigit(c)) {
                pos++;
            } else {
                break;
            }
        }
        if (pos == start) {
            throw new GraphQLSelectionParseException("Expected variable name after '$' at position " + (pos - 1));
        }
        return new Token(TokenKind.VARIABLE, input.substring(start, pos));
    }
}
