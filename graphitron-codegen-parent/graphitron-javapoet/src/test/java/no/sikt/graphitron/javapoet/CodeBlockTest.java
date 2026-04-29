/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Sikt for internal use in the Graphitron project.
 */
package no.sikt.graphitron.javapoet;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class CodeBlockTest {
    @Test
    public void equalsAndHashCode() {
        CodeBlock a = CodeBlock.builder().build();
        CodeBlock b = CodeBlock.builder().build();
        assertThat(a.equals(b)).isTrue();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        a = CodeBlock.builder().add("$L", "taco").build();
        b = CodeBlock.builder().add("$L", "taco").build();
        assertThat(a.equals(b)).isTrue();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    public void of() {
        CodeBlock a = CodeBlock.of("$L taco", "delicious");
        assertThat(a.toString()).isEqualTo("delicious taco");
    }

    @Test
    public void isEmpty() {
        assertTrue(CodeBlock.builder().isEmpty());
        assertTrue(CodeBlock.builder().add("").isEmpty());
        assertTrue(CodeBlock.builder().addIf(false, "a").isEmpty());
        assertFalse(CodeBlock.builder().add(" ").isEmpty());
    }

    @Test
    public void indentCannotBeIndexed() {
        try {
            CodeBlock.builder().add("$1>", "taco").build();
            fail();
        } catch (IllegalArgumentException exp) {
            assertThat(exp)
                    .hasMessage("$$, $>, $<, $[, $], $W, and $Z may not have an index");
        }
    }

    @Test
    public void deindentCannotBeIndexed() {
        try {
            CodeBlock.builder().add("$1<", "taco").build();
            fail();
        } catch (IllegalArgumentException exp) {
            assertThat(exp)
                    .hasMessage("$$, $>, $<, $[, $], $W, and $Z may not have an index");
        }
    }

    @Test
    public void dollarSignEscapeCannotBeIndexed() {
        try {
            CodeBlock.builder().add("$1$", "taco").build();
            fail();
        } catch (IllegalArgumentException exp) {
            assertThat(exp)
                    .hasMessage("$$, $>, $<, $[, $], $W, and $Z may not have an index");
        }
    }

    @Test
    public void statementBeginningCannotBeIndexed() {
        try {
            CodeBlock.builder().add("$1[", "taco").build();
            fail();
        } catch (IllegalArgumentException exp) {
            assertThat(exp)
                    .hasMessage("$$, $>, $<, $[, $], $W, and $Z may not have an index");
        }
    }

    @Test
    public void statementEndingCannotBeIndexed() {
        try {
            CodeBlock.builder().add("$1]", "taco").build();
            fail();
        } catch (IllegalArgumentException exp) {
            assertThat(exp)
                    .hasMessage("$$, $>, $<, $[, $], $W, and $Z may not have an index");
        }
    }

    @Test
    public void nameFormatCanBeIndexed() {
        CodeBlock block = CodeBlock.builder().add("$1N", "taco").build();
        assertThat(block.toString()).isEqualTo("taco");
    }

    @Test
    public void literalFormatCanBeIndexed() {
        CodeBlock block = CodeBlock.builder().add("$1L", "taco").build();
        assertThat(block.toString()).isEqualTo("taco");
    }

    @Test
    public void stringFormatCanBeIndexed() {
        CodeBlock block = CodeBlock.builder().add("$1S", "taco").build();
        assertThat(block.toString()).isEqualTo("\"taco\"");
    }

    @Test
    public void typeFormatCanBeIndexed() {
        CodeBlock block = CodeBlock.builder().add("$1T", String.class).build();
        assertThat(block.toString()).isEqualTo("java.lang.String");
    }

    @Test
    public void simpleNamedArgument() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", "taco");
        CodeBlock block = CodeBlock.builder().addNamed("$text:S", map).build();
        assertThat(block.toString()).isEqualTo("\"taco\"");
    }

    @Test
    public void repeatedNamedArgument() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", "tacos");
        CodeBlock block = CodeBlock.builder()
                .addNamed("\"I like \" + $text:S + \". Do you like \" + $text:S + \"?\"", map)
                .build();
        assertThat(block.toString()).isEqualTo(
                "\"I like \" + \"tacos\" + \". Do you like \" + \"tacos\" + \"?\"");
    }

    @Test
    public void namedAndNoArgFormat() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", "tacos");
        CodeBlock block = CodeBlock.builder()
                .addNamed("$>\n$text:L for $$3.50", map).build();
        assertThat(block.toString()).isEqualTo("\n  tacos for $3.50");
    }

    @Test
    public void missingNamedArgument() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            CodeBlock.builder().addNamed("$text:S", map).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("Missing named argument for $text");
        }
    }

    @Test
    public void lowerCaseNamed() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("Text", "tacos");
            CodeBlock block = CodeBlock.builder().addNamed("$Text:S", map).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("argument 'Text' must start with a lowercase character");
        }
    }

    @Test
    public void multipleNamedArguments() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pipe", System.class);
        map.put("text", "tacos");

        CodeBlock block = CodeBlock.builder()
                .addNamed("$pipe:T.out.println(\"Let's eat some $text:L\");", map)
                .build();

        assertThat(block.toString()).isEqualTo(
                "java.lang.System.out.println(\"Let's eat some tacos\");");
    }

    @Test
    public void namedNewline() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("clazz", Integer.class);
        CodeBlock block = CodeBlock.builder().addNamed("$clazz:T\n", map).build();
        assertThat(block.toString()).isEqualTo("java.lang.Integer\n");
    }

    @Test
    public void danglingNamed() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("clazz", Integer.class);
        try {
            CodeBlock.builder().addNamed("$clazz:T$", map).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("dangling $ at end");
        }
    }

    @Test
    public void indexTooHigh() {
        try {
            CodeBlock.builder().add("$2T", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("index 2 for '$2T' not in range (received 1 arguments)");
        }
    }

    @Test
    public void indexIsZero() {
        try {
            CodeBlock.builder().add("$0T", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("index 0 for '$0T' not in range (received 1 arguments)");
        }
    }

    @Test
    public void indexIsNegative() {
        try {
            CodeBlock.builder().add("$-1T", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("invalid format string: '$-1T'");
        }
    }

    @Test
    public void indexWithoutFormatType() {
        try {
            CodeBlock.builder().add("$1", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("dangling format characters in '$1'");
        }
    }

    @Test
    public void indexWithoutFormatTypeNotAtStringEnd() {
        try {
            CodeBlock.builder().add("$1 taco", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("invalid format string: '$1 taco'");
        }
    }

    @Test
    public void indexButNoArguments() {
        try {
            CodeBlock.builder().add("$1T").build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("index 1 for '$1T' not in range (received 0 arguments)");
        }
    }

    @Test
    public void formatIndicatorAlone() {
        try {
            CodeBlock.builder().add("$", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("dangling format characters in '$'");
        }
    }

    @Test
    public void formatIndicatorWithoutIndexOrFormatType() {
        try {
            CodeBlock.builder().add("$ tacoString", String.class).build();
            fail();
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessage("invalid format string: '$ tacoString'");
        }
    }

    @Test
    public void sameIndexCanBeUsedWithDifferentFormats() {
        CodeBlock block = CodeBlock.builder()
                .add("$1T.out.println($1S)", ClassName.get(System.class))
                .build();
        assertThat(block.toString()).isEqualTo("java.lang.System.out.println(\"java.lang.System\")");
    }

    @Test
    public void tooManyStatementEnters() {
        CodeBlock codeBlock = CodeBlock.builder().add("$[$[").build();
        try {
            // We can't report this error until rendering type because code blocks might be composed.
            codeBlock.toString();
            fail();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("statement enter $[ followed by statement enter $[");
        }
    }

    @Test
    public void statementExitWithoutStatementEnter() {
        CodeBlock codeBlock = CodeBlock.builder().add("$]").build();
        try {
            // We can't report this error until rendering type because code blocks might be composed.
            codeBlock.toString();
            fail();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("statement exit $] has no matching statement enter $[");
        }
    }

    @Test
    public void join() {
        List<CodeBlock> codeBlocks = new ArrayList<>();
        codeBlocks.add(CodeBlock.of("$S", "hello"));
        codeBlocks.add(CodeBlock.of("$T", ClassName.get("world", "World")));
        codeBlocks.add(CodeBlock.of("need tacos"));

        CodeBlock joined = CodeBlock.join(" || ", codeBlocks);
        assertThat(joined.toString()).isEqualTo("\"hello\" || world.World || need tacos");
    }

    @Test
    public void joining() {
        List<CodeBlock> codeBlocks = new ArrayList<>();
        codeBlocks.add(CodeBlock.of("$S", "hello"));
        codeBlocks.add(CodeBlock.of("$T", ClassName.get("world", "World")));
        codeBlocks.add(CodeBlock.of("need tacos"));

        CodeBlock joined = codeBlocks.stream().collect(CodeBlock.joining(" || "));
        assertThat(joined.toString()).isEqualTo("\"hello\" || world.World || need tacos");
    }

    @Test
    public void joiningSingle() {
        List<CodeBlock> codeBlocks = new ArrayList<>();
        codeBlocks.add(CodeBlock.of("$S", "hello"));

        CodeBlock joined = codeBlocks.stream().collect(CodeBlock.joining(" || "));
        assertThat(joined.toString()).isEqualTo("\"hello\"");
    }

    @Test
    public void joiningWithPrefixAndSuffix() {
        List<CodeBlock> codeBlocks = new ArrayList<>();
        codeBlocks.add(CodeBlock.of("$S", "hello"));
        codeBlocks.add(CodeBlock.of("$T", ClassName.get("world", "World")));
        codeBlocks.add(CodeBlock.of("need tacos"));

        CodeBlock joined = codeBlocks.stream().collect(CodeBlock.joining(" || ", "start {", "} end"));
        assertThat(joined.toString()).isEqualTo("start {\"hello\" || world.World || need tacos} end");
    }

    @Test
    public void clear() {
        CodeBlock block = CodeBlock.builder()
                .addStatement("$S", "Test string")
                .clear()
                .build();

        assertThat(block.toString()).isEmpty();
    }

    @Test
    public void ternary() {
        CodeBlock block = CodeBlock.ternary(
                CodeBlock.of("x != null"),
                CodeBlock.of("x"),
                CodeBlock.of("y"));
        assertThat(block.toString()).isEqualTo("x != null ? x : y");
    }

    @Test
    public void ternaryWithTypes() {
        CodeBlock block = CodeBlock.ternary(
                CodeBlock.of("$N != null", "value"),
                CodeBlock.of("$T.of($N)", ClassName.get("java.util", "List"), "value"),
                CodeBlock.of("$T.of()", ClassName.get("java.util", "List")));
        assertThat(block.toString()).isEqualTo("value != null ? java.util.List.of(value) : java.util.List.of()");
    }

    @Test
    public void ternaryOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .add("var x = ")
                .ternary(CodeBlock.of("a"), CodeBlock.of("b"), CodeBlock.of("c"))
                .build();
        assertThat(block.toString()).isEqualTo("var x = a ? b : c");
    }

    @Test
    public void ternaryIfOnBuilder() {
        CodeBlock withTernary = CodeBlock.builder()
                .ternaryIf(true, CodeBlock.of("a"), CodeBlock.of("b"), CodeBlock.of("c"))
                .build();
        assertThat(withTernary.toString()).isEqualTo("a ? b : c");

        CodeBlock without = CodeBlock.builder()
                .ternaryIf(false, CodeBlock.of("a"), CodeBlock.of("b"), CodeBlock.of("c"))
                .build();
        assertThat(without.isEmpty()).isTrue();
    }

    @Test
    public void staticMethodCallNoArgs() {
        CodeBlock block = CodeBlock.methodCall(ClassName.get("org.jooq", "DSL"), "trueCondition");
        assertThat(block.toString()).isEqualTo("org.jooq.DSL.trueCondition()");
    }

    @Test
    public void staticMethodCallWithArgs() {
        CodeBlock block = CodeBlock.methodCall(
                ClassName.get("org.jooq", "DSL"), "select",
                CodeBlock.of("field1"), CodeBlock.of("field2"));
        assertThat(block.toString()).isEqualTo("org.jooq.DSL.select(field1, field2)");
    }

    @Test
    public void staticMethodCallWithList() {
        List<CodeBlock> args = List.of(CodeBlock.of("a"), CodeBlock.of("b"), CodeBlock.of("c"));
        CodeBlock block = CodeBlock.methodCall(ClassName.get("java.util", "List"), "of", args);
        assertThat(block.toString()).isEqualTo("java.util.List.of(a, b, c)");
    }

    @Test
    public void staticMethodCallFiltersEmptyArgs() {
        CodeBlock block = CodeBlock.methodCall(
                ClassName.get("org.jooq", "DSL"), "row",
                CodeBlock.of("field1"), CodeBlock.empty(), CodeBlock.of("field2"));
        assertThat(block.toString()).isEqualTo("org.jooq.DSL.row(field1, field2)");
    }

    @Test
    public void instanceMethodCallNoArgs() {
        CodeBlock block = CodeBlock.methodCall("myVar", "toString");
        assertThat(block.toString()).isEqualTo("myVar.toString()");
    }

    @Test
    public void instanceMethodCallWithArgs() {
        CodeBlock block = CodeBlock.methodCall("record", "set",
                CodeBlock.of("$S", "name"), CodeBlock.of("value"));
        assertThat(block.toString()).isEqualTo("record.set(\"name\", value)");
    }

    @Test
    public void instanceMethodCallWithList() {
        List<CodeBlock> args = List.of(CodeBlock.of("x"), CodeBlock.of("y"));
        CodeBlock block = CodeBlock.methodCall("obj", "method", args);
        assertThat(block.toString()).isEqualTo("obj.method(x, y)");
    }

    @Test
    public void methodCallNoArgs() {
        CodeBlock block = CodeBlock.methodCall("doSomething");
        assertThat(block.toString()).isEqualTo("doSomething()");
    }

    @Test
    public void methodCallWithArgs() {
        CodeBlock block = CodeBlock.methodCall("process",
                CodeBlock.of("input"), CodeBlock.of("$S", "config"));
        assertThat(block.toString()).isEqualTo("process(input, \"config\")");
    }

    @Test
    public void methodCallWithList() {
        List<CodeBlock> args = List.of(CodeBlock.of("a"), CodeBlock.of("b"));
        CodeBlock block = CodeBlock.methodCall("compute", args);
        assertThat(block.toString()).isEqualTo("compute(a, b)");
    }

    @Test
    public void methodCallFiltersEmptyArgs() {
        CodeBlock block = CodeBlock.methodCall("foo",
                CodeBlock.empty(), CodeBlock.of("bar"), CodeBlock.empty());
        assertThat(block.toString()).isEqualTo("foo(bar)");
    }

    @Test
    public void methodCallOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .methodCall(ClassName.get("org.jooq", "DSL"), "select", CodeBlock.of("field1"))
                .build();
        assertThat(block.toString()).isEqualTo("org.jooq.DSL.select(field1)");
    }

    @Test
    public void instanceMethodCallOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .methodCall("ctx", "configuration")
                .build();
        assertThat(block.toString()).isEqualTo("ctx.configuration()");
    }

    @Test
    public void localMethodCallOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .methodCall("init", CodeBlock.of("env"))
                .build();
        assertThat(block.toString()).isEqualTo("init(env)");
    }

    @Test
    public void methodCallSingleArg() {
        CodeBlock block = CodeBlock.methodCall(
                ClassName.get("java.util", "Objects"), "requireNonNull",
                CodeBlock.of("value"));
        assertThat(block.toString()).isEqualTo("java.util.Objects.requireNonNull(value)");
    }

    @Test
    public void methodCallAllEmptyArgs() {
        CodeBlock block = CodeBlock.methodCall(
                ClassName.get("org.jooq", "DSL"), "row",
                CodeBlock.empty(), CodeBlock.empty());
        assertThat(block.toString()).isEqualTo("org.jooq.DSL.row()");
    }

    // --- assign ---

    @Test
    public void assign() {
        CodeBlock block = CodeBlock.assign("myVar", CodeBlock.of("getValue()"));
        assertThat(block.toString()).isEqualTo("myVar = getValue();\n");
    }

    @Test
    public void assignWithFormat() {
        CodeBlock block = CodeBlock.assign("result", "$T.of($N)", ClassName.get("java.util", "List"), "items");
        assertThat(block.toString()).isEqualTo("result = java.util.List.of(items);\n");
    }

    @Test
    public void assignOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .assign("x", CodeBlock.of("42"))
                .assign("y", CodeBlock.of("x + 1"))
                .build();
        assertThat(block.toString()).isEqualTo("x = 42;\ny = x + 1;\n");
    }

    @Test
    public void assignIfOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .assignIf(true, "x", CodeBlock.of("1"))
                .assignIf(false, "y", CodeBlock.of("2"))
                .build();
        assertThat(block.toString()).isEqualTo("x = 1;\n");
    }

    @Test
    public void assignIfWithFormatOnBuilder() {
        CodeBlock block = CodeBlock.builder()
                .assignIf(true, "x", "$L + $L", "a", "b")
                .assignIf(false, "y", "$L", "c")
                .build();
        assertThat(block.toString()).isEqualTo("x = a + b;\n");
    }

    // --- apply / applyIf ---

    @Test
    public void applyRunsTheTransform() {
        CodeBlock result = CodeBlock
                .builder()
                .add("inner")
                .apply(b -> CodeBlock.builder().add("wrap($L)", b.build()))
                .build();
        assertThat(result.toString()).isEqualTo("wrap(inner)");
    }

    @Test
    public void applyPreservesChain() {
        CodeBlock result = CodeBlock
                .builder()
                .add("a")
                .apply(b -> CodeBlock.builder().add("[$L]", b.build()))
                .add("b")
                .build();
        assertThat(result.toString()).isEqualTo("[a]b");
    }

    @Test
    public void applyIfTrueRunsTransform() {
        CodeBlock result = CodeBlock
                .builder()
                .add("x")
                .applyIf(true, b -> CodeBlock.builder().add("($L)", b.build()))
                .build();
        assertThat(result.toString()).isEqualTo("(x)");
    }

    @Test
    public void applyIfFalseIsIdentity() {
        CodeBlock result = CodeBlock
                .builder()
                .add("x")
                .applyIf(false, b -> CodeBlock.builder().add("($L)", b.build()))
                .build();
        assertThat(result.toString()).isEqualTo("x");
    }

    @Test
    public void applyFoldsTransformResultBackIntoReceiver() {
        CodeBlock.Builder builder = CodeBlock.builder().add("x");
        CodeBlock.Builder returned = builder.apply(b -> CodeBlock.builder().add("($L)", b.build()));
        assertThat(returned).isSameAs(builder);
        assertThat(builder.build().toString()).isEqualTo("(x)");
    }

    @Test
    public void applyIfTrueFoldsResultBackIntoReceiver() {
        CodeBlock.Builder builder = CodeBlock.builder().add("x");
        CodeBlock.Builder returned = builder.applyIf(true, b -> CodeBlock.builder().add("($L)", b.build()));
        assertThat(returned).isSameAs(builder);
        assertThat(builder.build().toString()).isEqualTo("(x)");
    }

    @Test
    public void applyIfFalseLeavesReceiverUnchanged() {
        CodeBlock.Builder builder = CodeBlock.builder().add("x");
        CodeBlock.Builder returned = builder.applyIf(false, b -> CodeBlock.builder().add("($L)", b.build()));
        assertThat(returned).isSameAs(builder);
        assertThat(builder.build().toString()).isEqualTo("x");
    }
}
