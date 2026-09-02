package no.sikt.graphitron.model.capture.graphitron;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Decodes federation's field-set grammar, the string form {@code @key(fields:)} carries, into the
 * ordered selections {@code graphitron_federation_key_field} stores, each one a list of segments.
 *
 * <p>The grammar is a parse boundary SQL cannot express, so it decodes at capture. Nesting is part
 * of the grammar and survives the decode as the segments of a selection ({@code "a { b c }"} yields
 * {@code [a, b]} and {@code [a, c]}), rather than being rendered back into a dotted string a reader
 * would have to take apart again; that today's consumer rejects nesting is a detection's business,
 * not a limit on what capture records.
 *
 * <p>Like every capture path this one is tolerant: a malformed field set yields whatever prefix
 * parsed and never throws, because the value arrives from a registry that validated nothing.
 */
final class FieldSetGrammar {

    private FieldSetGrammar() {}

    /**
     * The leaf selections of {@code fieldSet}, in written order, each as its segments from the
     * outermost inward. An unnested selection is one segment, so no list is empty.
     */
    static List<List<String>> paths(String fieldSet) {
        var paths = new ArrayList<List<String>>();
        Deque<String> prefix = new ArrayDeque<>();
        String pending = null;
        for (String token : tokenize(fieldSet)) {
            switch (token) {
                case "{" -> {
                    if (pending != null) {
                        prefix.addLast(pending);
                        pending = null;
                    }
                }
                case "}" -> {
                    if (pending != null) {
                        paths.add(qualify(prefix, pending));
                        pending = null;
                    }
                    if (!prefix.isEmpty()) {
                        prefix.removeLast();
                    }
                }
                default -> {
                    if (pending != null) {
                        paths.add(qualify(prefix, pending));
                    }
                    pending = token;
                }
            }
        }
        if (pending != null) {
            paths.add(qualify(prefix, pending));
        }
        return List.copyOf(paths);
    }

    private static List<String> qualify(Deque<String> prefix, String leaf) {
        var segments = new ArrayList<String>(prefix.size() + 1);
        segments.addAll(prefix);
        segments.add(leaf);
        return List.copyOf(segments);
    }

    /** Names and braces; whitespace and commas separate, everything else rides into a name. */
    private static List<String> tokenize(String fieldSet) {
        var tokens = new ArrayList<String>();
        var name = new StringBuilder();
        for (int i = 0; i < fieldSet.length(); i++) {
            char c = fieldSet.charAt(i);
            if (c == '{' || c == '}') {
                flush(name, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isWhitespace(c) || c == ',') {
                flush(name, tokens);
            } else {
                name.append(c);
            }
        }
        flush(name, tokens);
        return tokens;
    }

    private static void flush(StringBuilder name, List<String> tokens) {
        if (!name.isEmpty()) {
            tokens.add(name.toString());
            name.setLength(0);
        }
    }
}
