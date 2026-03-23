package no.sikt.graphitron.javapoet;

import org.junit.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public final class CodeBlocksTest {

    @Test
    public void createAndAddWithFormat() {
        CodeBlock result = CodeBlocks.create()
                .add("$L", "a")
                .add("$N", "b")
                .join(", ");
        assertThat(result.toString()).isEqualTo("a, b");
    }

    @Test
    public void conditionalAdd() {
        CodeBlock result = CodeBlocks.create()
                .add("first")
                .addIf(true, "second")
                .addIf(false, "skipped")
                .add("third")
                .join(", ");
        assertThat(result.toString()).isEqualTo("first, second, third");
    }

    @Test
    public void conditionalAddWithSupplier() {
        CodeBlock result = CodeBlocks.create()
                .add("a")
                .addIf(true, () -> CodeBlock.of("b"))
                .addIf(false, () -> { throw new AssertionError("should not be called"); })
                .join(", ");
        assertThat(result.toString()).isEqualTo("a, b");
    }

    @Test
    public void emptyBlocksAreFilteredOnJoin() {
        CodeBlock result = CodeBlocks.create()
                .add("first")
                .add(CodeBlock.empty())
                .add("second")
                .addIf(false, "skipped")
                .join(", ");
        assertThat(result.toString()).isEqualTo("first, second");
    }

    @Test
    public void joinWithNoSeparator() {
        CodeBlock result = CodeBlocks.create()
                .add("a")
                .add("b")
                .join();
        assertThat(result.toString()).isEqualTo("ab");
    }

    @Test
    public void ofFactory() {
        CodeBlock result = CodeBlocks
                .of(CodeBlock.of("a"), CodeBlock.of("b"), CodeBlock.of("c"))
                .join(", ");
        assertThat(result.toString()).isEqualTo("a, b, c");
    }

    @Test
    public void fromIterable() {
        var list = List.of(CodeBlock.of("x"), CodeBlock.of("y"));
        CodeBlock result = CodeBlocks.from(list).join(" + ");
        assertThat(result.toString()).isEqualTo("x + y");
    }

    @Test
    public void addAllFromCodeBlocks() {
        var first = CodeBlocks.create().add("a").add("b");
        var second = CodeBlocks.create().add("c").add("d");
        CodeBlock result = first.addAll(second).join(", ");
        assertThat(result.toString()).isEqualTo("a, b, c, d");
    }

    @Test
    public void addAllFromStream() {
        CodeBlock result = CodeBlocks.create()
                .add("start")
                .addAll(Stream.of("x", "y").map(CodeBlock::of))
                .join(", ");
        assertThat(result.toString()).isEqualTo("start, x, y");
    }

    @Test
    public void collector() {
        CodeBlocks collected = Stream.of("a", "b", "c")
                .map(CodeBlock::of)
                .collect(CodeBlocks.collector());
        assertThat(collected.size()).isEqualTo(3);
        assertThat(collected.join(", ").toString()).isEqualTo("a, b, c");
    }

    @Test
    public void mapTransformsElements() {
        CodeBlock result = CodeBlocks.create()
                .add("a")
                .add("b")
                .map(block -> CodeBlock.of("[$L]", block))
                .join(", ");
        assertThat(result.toString()).isEqualTo("[a], [b]");
    }

    @Test
    public void emptyCodeBlocks() {
        var blocks = CodeBlocks.create();
        assertThat(blocks.isEmpty()).isTrue();
        assertThat(blocks.size()).isEqualTo(0);
        assertThat(blocks.join(", ").isEmpty()).isTrue();
    }

    @Test
    public void iterableAndToList() {
        var blocks = CodeBlocks.of(CodeBlock.of("a"), CodeBlock.of("b"));
        assertThat(blocks.toList()).hasSize(2);
        assertThat(blocks.size()).isEqualTo(2);
    }

    @Test
    public void typicalGeneratorPattern() {
        boolean hasPagination = true;
        boolean hasTransform = false;

        CodeBlock result = CodeBlocks.create()
                .add("queryFunction")
                .addIf(hasPagination, "pageSize")
                .addIf(hasTransform, "transformFunction")
                .join(",\n");

        assertThat(result.toString()).isEqualTo("queryFunction,\npageSize");
    }
}
