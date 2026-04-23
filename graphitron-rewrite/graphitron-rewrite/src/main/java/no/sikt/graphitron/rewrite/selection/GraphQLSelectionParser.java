package no.sikt.graphitron.rewrite.selection;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for an extended GraphQL selection-set syntax.
 *
 * <h2>Standard subset supported</h2>
 * <pre>
 * SelectionSet  ::= '{' Field+ '}'
 * Field         ::= Alias? Name Arguments? SelectionSet?
 * Alias         ::= Name ':'
 * Arguments     ::= '(' Argument+ ')'
 * Argument      ::= Name ':' Value
 * Value         ::= StringValue | IntValue | FloatValue | BooleanValue
 *                 | NullValue | EnumValue | ListValue | ObjectValue | Variable
 * Variable      ::= '$' Name
 * </pre>
 *
 * <h2>Extensions</h2>
 * <ul>
 *   <li><b>Naked selection sets</b> – the surrounding {@code { }} braces may be omitted when
 *       the input is a flat list of fields (e.g. {@code id alias: value field(arg: "x")}).</li>
 *   <li><b>Dots in field names</b> – a field name may contain {@code .} characters
 *       (e.g. {@code some.dotted.field}).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Bracketed
 * List<ParsedField> fields = GraphQLSelectionParser.parse("{ id name address.city }");
 *
 * // Naked
 * List<ParsedField> fields = GraphQLSelectionParser.parse("id name address.city");
 *
 * // With alias and arguments
 * List<ParsedField> fields = GraphQLSelectionParser.parse(
 *     "id  fullName: name  search(query: \"foo\", limit: 10)");
 * }</pre>
 */
public final class GraphQLSelectionParser {

    private GraphQLSelectionParser() {}

    /**
     * Parses {@code input} as a (possibly naked) GraphQL selection set and returns
     * the list of top-level {@link ParsedField} nodes.
     *
     * @param input the selection set string, with or without surrounding braces
     * @return the parsed fields; never {@code null}, may be empty
     * @throws GraphQLSelectionParseException if the input cannot be parsed
     */
    public static List<ParsedField> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        var lexer = new Lexer(input);
        var parser = new Parser(lexer);
        return parser.parseTopLevel();
    }

    // -------------------------------------------------------------------------
    // Internal parser
    // -------------------------------------------------------------------------

    private static final class Parser {

        private final Lexer lexer;

        Parser(Lexer lexer) {
            this.lexer = lexer;
        }

        /**
         * Entry point: accepts either {@code { … }} or a bare list of fields.
         */
        List<ParsedField> parseTopLevel() {
            if (lexer.peek().kind() == TokenKind.LBRACE) {
                return parseBracketedSelectionSet();
            }
            return parseSelectionList(TokenKind.EOF);
        }

        // -- selection set --------------------------------------------------------

        private List<ParsedField> parseBracketedSelectionSet() {
            expect(TokenKind.LBRACE);
            List<ParsedField> fields = parseSelectionList(TokenKind.RBRACE);
            expect(TokenKind.RBRACE);
            return fields;
        }

        /**
         * Reads fields until {@code terminator} or EOF is seen (without consuming
         * the terminator).
         */
        private List<ParsedField> parseSelectionList(TokenKind terminator) {
            List<ParsedField> fields = new ArrayList<>();
            while (lexer.peek().kind() != terminator && lexer.peek().kind() != TokenKind.EOF) {
                fields.add(parseField());
            }
            return fields;
        }

        // -- field ----------------------------------------------------------------

        private ParsedField parseField() {
            String first = expectName();

            String alias;
            String name;
            if (lexer.peek().kind() == TokenKind.COLON) {
                // alias : name
                lexer.next(); // consume ':'
                alias = first;
                name = expectName();
            } else {
                alias = null;
                name = first;
            }

            List<ParsedArgument> arguments = List.of();
            if (lexer.peek().kind() == TokenKind.LPAREN) {
                arguments = parseArguments();
            }

            List<ParsedField> selectionSet = List.of();
            if (lexer.peek().kind() == TokenKind.LBRACE) {
                selectionSet = parseBracketedSelectionSet();
            }

            return new ParsedField(alias, name, arguments, selectionSet);
        }

        // -- arguments ------------------------------------------------------------

        private List<ParsedArgument> parseArguments() {
            expect(TokenKind.LPAREN);
            List<ParsedArgument> args = new ArrayList<>();
            while (lexer.peek().kind() != TokenKind.RPAREN) {
                if (lexer.peek().kind() == TokenKind.EOF) {
                    throw new GraphQLSelectionParseException("Unterminated argument list");
                }
                args.add(parseArgument());
            }
            expect(TokenKind.RPAREN);
            return args;
        }

        private ParsedArgument parseArgument() {
            String name = expectName();
            expect(TokenKind.COLON);
            ParsedValue value = parseValue();
            return new ParsedArgument(name, value);
        }

        // -- values ---------------------------------------------------------------

        private ParsedValue parseValue() {
            Token t = lexer.peek();
            return switch (t.kind()) {
                case STRING -> {
                    lexer.next();
                    yield new ParsedValue.StringValue(t.value());
                }
                case INT -> {
                    lexer.next();
                    yield new ParsedValue.IntValue(Long.parseLong(t.value()));
                }
                case FLOAT -> {
                    lexer.next();
                    yield new ParsedValue.FloatValue(Double.parseDouble(t.value()));
                }
                case VARIABLE -> {
                    lexer.next();
                    yield new ParsedValue.VariableValue(t.value());
                }
                case LBRACKET -> parseListValue();
                case LBRACE -> parseObjectValue();
                case NAME -> {
                    lexer.next();
                    yield switch (t.value()) {
                        case "true" -> new ParsedValue.BooleanValue(true);
                        case "false" -> new ParsedValue.BooleanValue(false);
                        case "null" -> new ParsedValue.NullValue();
                        default -> new ParsedValue.EnumValue(t.value());
                    };
                }
                default -> throw new GraphQLSelectionParseException(
                        "Expected a value but got " + t);
            };
        }

        private ParsedValue parseListValue() {
            expect(TokenKind.LBRACKET);
            List<ParsedValue> values = new ArrayList<>();
            while (lexer.peek().kind() != TokenKind.RBRACKET) {
                if (lexer.peek().kind() == TokenKind.EOF) {
                    throw new GraphQLSelectionParseException("Unterminated list value");
                }
                values.add(parseValue());
            }
            expect(TokenKind.RBRACKET);
            return new ParsedValue.ListValue(values);
        }

        private ParsedValue parseObjectValue() {
            expect(TokenKind.LBRACE);
            List<ParsedArgument> fields = new ArrayList<>();
            while (lexer.peek().kind() != TokenKind.RBRACE) {
                if (lexer.peek().kind() == TokenKind.EOF) {
                    throw new GraphQLSelectionParseException("Unterminated object value");
                }
                fields.add(parseArgument());
            }
            expect(TokenKind.RBRACE);
            return new ParsedValue.ObjectValue(fields);
        }

        // -- helpers --------------------------------------------------------------

        private String expectName() {
            Token t = lexer.next();
            if (t.kind() != TokenKind.NAME) {
                throw new GraphQLSelectionParseException(
                        "Expected a field name but got " + t);
            }
            return t.value();
        }

        private void expect(TokenKind kind) {
            Token t = lexer.next();
            if (t.kind() != kind) {
                throw new GraphQLSelectionParseException(
                        "Expected " + kind + " but got " + t);
            }
        }
    }
}
