package no.sikt.graphitron.javapoet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * An append-only, ordered collection of {@link CodeBlock} instances with ergonomic methods for
 * conditional addition, transformation, and joining.
 *
 * <p>Use this instead of {@code ArrayList<CodeBlock>} when you need to accumulate code blocks
 * and join them later. Empty blocks are automatically filtered on {@link #join}.
 *
 * <pre>{@code
 * CodeBlock result = CodeBlocks.create()
 *     .add("$T.foo()", SomeClass.class)
 *     .addIf(needsBar, ".bar()")
 *     .add(".baz($L)", value)
 *     .join(",\n");
 * }</pre>
 */
public final class CodeBlocks implements Iterable<CodeBlock> {
    private final List<CodeBlock> blocks;

    private CodeBlocks(List<CodeBlock> blocks) {
        this.blocks = blocks;
    }

    /**
     * Creates an empty {@link CodeBlocks}.
     */
    public static CodeBlocks create() {
        return new CodeBlocks(new ArrayList<>());
    }

    /**
     * Creates a {@link CodeBlocks} containing the given blocks.
     */
    public static CodeBlocks of(CodeBlock... blocks) {
        var result = new CodeBlocks(new ArrayList<>(blocks.length));
        Collections.addAll(result.blocks, blocks);
        return result;
    }

    /**
     * Creates a {@link CodeBlocks} from an existing iterable.
     */
    public static CodeBlocks from(Iterable<CodeBlock> blocks) {
        return create().addAll(blocks);
    }

    /**
     * Returns a {@link Collector} that accumulates {@link CodeBlock} elements into a {@link CodeBlocks}.
     */
    public static Collector<CodeBlock, ?, CodeBlocks> collector() {
        return Collector.of(
                CodeBlocks::create,
                CodeBlocks::addBlock,
                (a, b) -> { a.blocks.addAll(b.blocks); return a; }
        );
    }

    /**
     * Appends a code block built from the given format and arguments.
     */
    public CodeBlocks add(String format, Object... args) {
        return add(CodeBlock.of(format, args));
    }

    /**
     * Appends a pre-built code block.
     */
    public CodeBlocks add(CodeBlock block) {
        blocks.add(block);
        return this;
    }

    /**
     * Appends a code block built from the given variable.
     */
    public CodeBlocks addVar(String variable) {
        return add("$N", variable);
    }

    /**
     * Appends a code block only if the predicate is true.
     */
    public CodeBlocks addIf(boolean predicate, String format, Object... args) {
        return predicate ? add(CodeBlock.of(format, args)) : this;
    }

    /**
     * Appends a pre-built code block only if the predicate is true.
     */
    public CodeBlocks addIf(boolean predicate, CodeBlock block) {
        return predicate ? add(block) : this;
    }

    /**
     * Appends a lazily-built code block only if the predicate is true.
     */
    public CodeBlocks addIf(boolean predicate, Supplier<CodeBlock> supplier) {
        if (predicate) {
            return add(supplier.get());
        }
        return this;
    }

    /**
     * Appends all code blocks from another {@link CodeBlocks}.
     */
    public CodeBlocks addAll(CodeBlocks other) {
        blocks.addAll(other.blocks);
        return this;
    }

    /**
     * Appends all code blocks from an iterable.
     */
    public CodeBlocks addAll(Iterable<CodeBlock> other) {
        other.forEach(blocks::add);
        return this;
    }

    /**
     * Appends all code blocks from a stream.
     */
    public CodeBlocks addAll(Stream<CodeBlock> stream) {
        stream.forEach(blocks::add);
        return this;
    }

    /**
     * Returns a new {@link CodeBlocks} with the given function applied to each element.
     */
    public CodeBlocks map(Function<CodeBlock, CodeBlock> transform) {
        var result = new CodeBlocks(new ArrayList<>(blocks.size()));
        for (var block : blocks) {
            result.blocks.add(transform.apply(block));
        }
        return result;
    }

    /**
     * Joins all non-empty blocks with the given separator.
     */
    public CodeBlock join(String separator) {
        return CodeBlock.join(blocks, separator);
    }

    /**
     * Joins all non-empty blocks with no separator.
     */
    public CodeBlock join() {
        return CodeBlock.join(blocks);
    }

    /**
     * Returns true if this collection contains no blocks.
     */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * Returns the number of blocks in this collection.
     */
    public int size() {
        return blocks.size();
    }

    /**
     * Returns an unmodifiable view of the underlying list.
     */
    public List<CodeBlock> toList() {
        return Collections.unmodifiableList(blocks);
    }

    /**
     * Returns a sequential stream over the blocks.
     */
    public Stream<CodeBlock> stream() {
        return blocks.stream();
    }

    @Override
    public Iterator<CodeBlock> iterator() {
        return Collections.unmodifiableList(blocks).iterator();
    }

    private void addBlock(CodeBlock block) {
        blocks.add(block);
    }
}
