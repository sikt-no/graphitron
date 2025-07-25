package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.definitions.interfaces.JoinElement;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.asMethodCall;

/**
 * A list of table or key references.
 */
public class JoinListSequence extends LinkedList<JoinElement> {
    /**
     * @return Render all the elements of this list to a method call sequence.
     */
    public CodeBlock render() {
        return render(null);
    }

    /**
     * @return Render the elements of this list to a method call sequence, but exclude everything after the limit element.
     */
    public CodeBlock render(JoinElement limitElement) {
        if (isEmpty()) {
            return CodeBlock.empty();
        }

        var first = getFirst();
        var code = CodeBlock.builder().add(first.getMappingName());
        if (first.equals(limitElement)) {
            return code.build();
        }

        if (size() > 1) {
            var remaining = this.subList(1, size());
            for (var element : remaining) {
                if (element.clearsPreviousSequence()) {
                    code.clear().add(element.getMappingName());
                } else {
                    code.add(asMethodCall(element.getCodeName()));
                }

                if (element.equals(limitElement)) {
                    return code.build();
                }
            }
        }
        return code.build();
    }

    public static JoinListSequence of(JoinElement ... elements) {
        var sequence = new JoinListSequence();
        if (!Arrays.stream(elements).allMatch(Objects::isNull)) {
            sequence.addAll(List.of(elements));
        }
        return sequence;
    }

    public JoinListSequence cloneAdd(JoinElement ... elements) {
        var clone = clone();
        if (!Arrays.stream(elements).allMatch(Objects::isNull)) {
            clone.addAll(List.of(elements));
        }
        return clone;
    }

    public JoinElement getSecondLast() {
        var it = descendingIterator();
        if (it.hasNext()) {
            it.next();
        }
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public JoinListSequence clone() {
        return (JoinListSequence) super.clone();
    }

    @Override
    public String toString() {
        return stream().filter(it -> !it.clearsPreviousSequence()).map(JoinElement::getCodeName).collect(Collectors.joining("_"));
    }
}
