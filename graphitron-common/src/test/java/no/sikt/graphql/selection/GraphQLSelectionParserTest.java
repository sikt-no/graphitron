package no.sikt.graphql.selection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GraphQLSelectionParser")
class GraphQLSelectionParserTest {

    // -------------------------------------------------------------------------
    // Null / blank / empty
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null input returns empty list")
    void nullReturnsEmpty() {
        assertThat(GraphQLSelectionParser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("blank input returns empty list")
    void blankReturnsEmpty() {
        assertThat(GraphQLSelectionParser.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("empty braces return empty list")
    void emptyBracesReturnEmpty() {
        assertThat(GraphQLSelectionParser.parse("{}")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Bracketed selection sets
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Bracketed selection sets")
    class Bracketed {

        @Test
        @DisplayName("single field")
        void singleField() {
            var fields = GraphQLSelectionParser.parse("{ id }");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), null, "id");
        }

        @Test
        @DisplayName("multiple fields")
        void multipleFields() {
            var fields = GraphQLSelectionParser.parse("{ id name email }");

            assertThat(fields).extracting(ParsedField::name)
                    .containsExactly("id", "name", "email");
        }

        @Test
        @DisplayName("comma-separated fields (commas are insignificant)")
        void commaSeparated() {
            var fields = GraphQLSelectionParser.parse("{ id, name, email }");

            assertThat(fields).extracting(ParsedField::name)
                    .containsExactly("id", "name", "email");
        }

        @Test
        @DisplayName("alias: field")
        void withAlias() {
            var fields = GraphQLSelectionParser.parse("{ myId: id }");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), "myId", "id");
        }

        @Test
        @DisplayName("nested selection set")
        void nested() {
            var fields = GraphQLSelectionParser.parse("{ user { id name } }");

            assertThat(fields).hasSize(1);
            var user = fields.get(0);
            assertField(user, null, "user");
            assertThat(user.selectionSet()).extracting(ParsedField::name)
                    .containsExactly("id", "name");
        }
    }

    // -------------------------------------------------------------------------
    // Naked (brace-free) selection sets
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Naked selection sets (no braces)")
    class Naked {

        @Test
        @DisplayName("single field")
        void singleField() {
            var fields = GraphQLSelectionParser.parse("id");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), null, "id");
        }

        @Test
        @DisplayName("multiple whitespace-separated fields")
        void multipleFields() {
            var fields = GraphQLSelectionParser.parse("id name email");

            assertThat(fields).extracting(ParsedField::name)
                    .containsExactly("id", "name", "email");
        }

        @Test
        @DisplayName("alias: field in naked mode")
        void withAlias() {
            var fields = GraphQLSelectionParser.parse("alias: value");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), "alias", "value");
        }

        @Test
        @DisplayName("mixed plain, aliased, and argument fields in naked mode")
        void mixed() {
            var fields = GraphQLSelectionParser.parse("id alias: value field(arg: \"input\")");

            assertThat(fields).hasSize(3);
            assertField(fields.get(0), null, "id");
            assertField(fields.get(1), "alias", "value");
            assertField(fields.get(2), null, "field");
            assertThat(fields.get(2).arguments()).hasSize(1);
        }

        @Test
        @DisplayName("naked field with nested braced selection set")
        void nestedSelectionSet() {
            var fields = GraphQLSelectionParser.parse("user { id name }");

            assertThat(fields).hasSize(1);
            var user = fields.get(0);
            assertField(user, null, "user");
            assertThat(user.selectionSet()).extracting(ParsedField::name)
                    .containsExactly("id", "name");
        }
    }

    // -------------------------------------------------------------------------
    // Dots in field names
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Dots in field names")
    class DottedNames {

        @Test
        @DisplayName("simple dotted name")
        void simpleDotted() {
            var fields = GraphQLSelectionParser.parse("address.city");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), null, "address.city");
        }

        @Test
        @DisplayName("deeply dotted name")
        void deeplyDotted() {
            var fields = GraphQLSelectionParser.parse("a.b.c.d");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), null, "a.b.c.d");
        }

        @Test
        @DisplayName("dotted name with alias")
        void dottedWithAlias() {
            var fields = GraphQLSelectionParser.parse("city: address.city");

            assertThat(fields).hasSize(1);
            assertField(fields.get(0), "city", "address.city");
        }

        @Test
        @DisplayName("dotted name with argument")
        void dottedWithArgument() {
            var fields = GraphQLSelectionParser.parse("address.city(lang: \"en\")");

            assertThat(fields).hasSize(1);
            var f = fields.get(0);
            assertField(f, null, "address.city");
            assertThat(f.arguments()).hasSize(1);
            assertArgument(f.arguments().get(0), "lang", new ParsedValue.StringValue("en"));
        }

        @Test
        @DisplayName("multiple dotted names in bracketed set")
        void multipleDottedBracketed() {
            var fields = GraphQLSelectionParser.parse("{ id address.city address.zip }");

            assertThat(fields).extracting(ParsedField::name)
                    .containsExactly("id", "address.city", "address.zip");
        }
    }

    // -------------------------------------------------------------------------
    // Arguments
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("string argument")
        void stringArgument() {
            var fields = GraphQLSelectionParser.parse("search(query: \"hello\")");

            assertArgument(fields.get(0).arguments().get(0), "query", new ParsedValue.StringValue("hello"));
        }

        @Test
        @DisplayName("integer argument")
        void intArgument() {
            var fields = GraphQLSelectionParser.parse("page(limit: 10)");

            assertArgument(fields.get(0).arguments().get(0), "limit", new ParsedValue.IntValue(10));
        }

        @Test
        @DisplayName("negative integer argument")
        void negativeIntArgument() {
            var fields = GraphQLSelectionParser.parse("offset(value: -5)");

            assertArgument(fields.get(0).arguments().get(0), "value", new ParsedValue.IntValue(-5));
        }

        @Test
        @DisplayName("float argument")
        void floatArgument() {
            var fields = GraphQLSelectionParser.parse("score(min: 3.14)");

            assertArgument(fields.get(0).arguments().get(0), "min", new ParsedValue.FloatValue(3.14));
        }

        @Test
        @DisplayName("boolean true argument")
        void boolTrueArgument() {
            var fields = GraphQLSelectionParser.parse("flag(active: true)");

            assertArgument(fields.get(0).arguments().get(0), "active", new ParsedValue.BooleanValue(true));
        }

        @Test
        @DisplayName("boolean false argument")
        void boolFalseArgument() {
            var fields = GraphQLSelectionParser.parse("flag(deleted: false)");

            assertArgument(fields.get(0).arguments().get(0), "deleted", new ParsedValue.BooleanValue(false));
        }

        @Test
        @DisplayName("null argument")
        void nullArgument() {
            var fields = GraphQLSelectionParser.parse("f(x: null)");

            assertArgument(fields.get(0).arguments().get(0), "x", new ParsedValue.NullValue());
        }

        @Test
        @DisplayName("enum argument")
        void enumArgument() {
            var fields = GraphQLSelectionParser.parse("f(status: ACTIVE)");

            assertArgument(fields.get(0).arguments().get(0), "status", new ParsedValue.EnumValue("ACTIVE"));
        }

        @Test
        @DisplayName("variable argument")
        void variableArgument() {
            var fields = GraphQLSelectionParser.parse("f(id: $userId)");

            assertArgument(fields.get(0).arguments().get(0), "id", new ParsedValue.VariableValue("userId"));
        }

        @Test
        @DisplayName("list argument")
        void listArgument() {
            var fields = GraphQLSelectionParser.parse("f(ids: [1, 2, 3])");

            var arg = fields.get(0).arguments().get(0);
            assertThat(arg.name()).isEqualTo("ids");
            assertThat(arg.value()).isInstanceOf(ParsedValue.ListValue.class);
            var list = (ParsedValue.ListValue) arg.value();
            assertThat(list.values()).containsExactly(
                    new ParsedValue.IntValue(1),
                    new ParsedValue.IntValue(2),
                    new ParsedValue.IntValue(3));
        }

        @Test
        @DisplayName("object argument")
        void objectArgument() {
            var fields = GraphQLSelectionParser.parse("f(filter: {name: \"Alice\", age: 30})");

            var arg = fields.get(0).arguments().get(0);
            assertThat(arg.name()).isEqualTo("filter");
            assertThat(arg.value()).isInstanceOf(ParsedValue.ObjectValue.class);
            var obj = (ParsedValue.ObjectValue) arg.value();
            assertThat(obj.fields()).hasSize(2);
            assertArgument(obj.fields().get(0), "name", new ParsedValue.StringValue("Alice"));
            assertArgument(obj.fields().get(1), "age", new ParsedValue.IntValue(30));
        }

        @Test
        @DisplayName("multiple arguments")
        void multipleArguments() {
            var fields = GraphQLSelectionParser.parse("search(query: \"foo\", limit: 10, active: true)");

            var args = fields.get(0).arguments();
            assertThat(args).hasSize(3);
            assertArgument(args.get(0), "query", new ParsedValue.StringValue("foo"));
            assertArgument(args.get(1), "limit", new ParsedValue.IntValue(10));
            assertArgument(args.get(2), "active", new ParsedValue.BooleanValue(true));
        }

        @Test
        @DisplayName("string with escape sequences")
        void stringEscapes() {
            var fields = GraphQLSelectionParser.parse("f(msg: \"hello\\nworld\")");

            assertArgument(fields.get(0).arguments().get(0), "msg",
                    new ParsedValue.StringValue("hello\nworld"));
        }
    }

    // -------------------------------------------------------------------------
    // Combinations
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Combined features")
    class Combined {

        @Test
        @DisplayName("alias + dotted name + argument")
        void aliasAndDottedAndArg() {
            var fields = GraphQLSelectionParser.parse("loc: address.city(lang: \"en\")");

            assertThat(fields).hasSize(1);
            var f = fields.get(0);
            assertField(f, "loc", "address.city");
            assertThat(f.arguments()).hasSize(1);
            assertArgument(f.arguments().get(0), "lang", new ParsedValue.StringValue("en"));
        }

        @Test
        @DisplayName("deeply nested selection sets")
        void deeplyNested() {
            var fields = GraphQLSelectionParser.parse("{ a { b { c } } }");

            assertThat(fields).hasSize(1);
            var a = fields.get(0);
            assertField(a, null, "a");
            var b = a.selectionSet().get(0);
            assertField(b, null, "b");
            var c = b.selectionSet().get(0);
            assertField(c, null, "c");
        }

        @Test
        @DisplayName("comment lines are ignored")
        void commentLines() {
            var fields = GraphQLSelectionParser.parse("""
                    # This is a comment
                    id # inline comment
                    name
                    """);

            assertThat(fields).extracting(ParsedField::name)
                    .containsExactly("id", "name");
        }

        @Test
        @DisplayName("multiline naked selection set")
        void multiline() {
            var fields = GraphQLSelectionParser.parse("""
                    id
                    name
                    email
                    """);

            assertThat(fields).extracting(ParsedField::name)
                    .containsExactly("id", "name", "email");
        }
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("unclosed brace throws")
        void unclosedBrace() {
            assertThatThrownBy(() -> GraphQLSelectionParser.parse("{ id name"))
                    .isInstanceOf(GraphQLSelectionParseException.class);
        }

        @Test
        @DisplayName("unclosed argument list throws")
        void unclosedArguments() {
            assertThatThrownBy(() -> GraphQLSelectionParser.parse("f(arg: \"val\""))
                    .isInstanceOf(GraphQLSelectionParseException.class);
        }

        @Test
        @DisplayName("unexpected character throws")
        void unexpectedCharacter() {
            assertThatThrownBy(() -> GraphQLSelectionParser.parse("id @ name"))
                    .isInstanceOf(GraphQLSelectionParseException.class);
        }

        @Test
        @DisplayName("unterminated string throws")
        void unterminatedString() {
            assertThatThrownBy(() -> GraphQLSelectionParser.parse("f(x: \"unclosed)"))
                    .isInstanceOf(GraphQLSelectionParseException.class);
        }

        @Test
        @DisplayName("alias colon without following name throws")
        void aliasMissingName() {
            assertThatThrownBy(() -> GraphQLSelectionParser.parse("alias: 123"))
                    .isInstanceOf(GraphQLSelectionParseException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void assertField(ParsedField field, String expectedAlias, String expectedName) {
        assertThat(field.alias()).as("alias").isEqualTo(expectedAlias);
        assertThat(field.name()).as("name").isEqualTo(expectedName);
    }

    private static void assertArgument(ParsedArgument arg, String expectedName, ParsedValue expectedValue) {
        assertThat(arg.name()).as("argument name").isEqualTo(expectedName);
        assertThat(arg.value()).as("argument value").isEqualTo(expectedValue);
    }
}
