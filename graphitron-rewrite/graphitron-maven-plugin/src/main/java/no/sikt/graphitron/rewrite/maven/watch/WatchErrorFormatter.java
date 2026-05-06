package no.sikt.graphitron.rewrite.maven.watch;

import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders a {@link ValidationError} list into a grouped, line-wrapped tree intended for the
 * {@code graphitron:dev} loop. Output is deterministic and stable across cycles so a
 * downstream delta tracker can compare line-by-line.
 *
 * <p>Layout (one block per source file):
 * <pre>
 * &lt;source-file&gt; (N error(s))
 *   schema:                                     (block omitted if empty)
 *     line:col  [kind]  message
 *   type &lt;TypeName&gt;                            (block per type)
 *     line:col  [kind]  message                 (errors attached to the type itself)
 *     &lt;TypeName&gt;.&lt;fieldName&gt;  (M error(s))    (subgroup per field on this type)
 *       line:col  [kind]  message
 * </pre>
 *
 * <p>"Schema-level" errors are those whose {@link ValidationError#coordinate()} is {@code null}.
 * "Type-level" coordinates are bare type names (no dot); "field-level" coordinates are
 * {@code Type.field}. Within each leaf bucket, errors sort by {@code (line, column)} so the order
 * matches how the schema reads top-to-bottom in an editor.
 *
 * <p>The formatter never logs; it returns a string the caller chooses to print or test against.
 */
public final class WatchErrorFormatter {

    private static final String FILE_INDENT   = "";
    private static final String GROUP_INDENT  = "  ";
    private static final String FIELD_INDENT  = "    ";
    private static final String LEAF_INDENT   = "      ";
    private static final String TYPE_LEAF_INDENT = "    ";
    private static final String SCHEMA_LEAF_INDENT = "    ";

    private WatchErrorFormatter() {}

    /**
     * Renders the supplied errors as a tree, plus a one-line kind summary and an optional
     * delta line if {@code previous} is non-null.
     */
    public static String format(List<ValidationError> errors, Set<DeltaKey> previous) {
        if (errors.isEmpty()) {
            return "graphitron:dev: no validation errors";
        }
        var byFile = groupByFile(errors);
        StringBuilder sb = new StringBuilder();
        for (var fileEntry : byFile.entrySet()) {
            renderFile(sb, fileEntry.getKey(), fileEntry.getValue());
        }
        sb.append('\n').append(summary(errors));
        if (previous != null) {
            String delta = delta(errors, previous);
            if (!delta.isEmpty()) {
                sb.append("   |   ").append(delta);
            }
        }
        return sb.toString();
    }

    /**
     * Computes the delta-tracking key set for the supplied error list. The key strips the
     * {@link ValidationError#location() location} so unrelated line shifts (a comment added
     * above) do not flag every error as new.
     */
    public static Set<DeltaKey> keysOf(List<ValidationError> errors) {
        return errors.stream().map(DeltaKey::of).collect(Collectors.toUnmodifiableSet());
    }

    private static void renderFile(StringBuilder sb, String file, List<ValidationError> fileErrors) {
        sb.append(FILE_INDENT).append(file).append(" (").append(fileErrors.size())
            .append(fileErrors.size() == 1 ? " error)\n" : " errors)\n");

        var schemaLevel = fileErrors.stream()
            .filter(e -> e.coordinate() == null)
            .sorted(byLineCol())
            .toList();
        if (!schemaLevel.isEmpty()) {
            sb.append(GROUP_INDENT).append("schema:\n");
            schemaLevel.forEach(e -> appendLeaf(sb, SCHEMA_LEAF_INDENT, e));
        }

        // Group type-or-field-level errors by type name (the part of the coordinate before the
        // first dot). Preserve first-seen order so the report tracks SDL declaration order
        // when errors share a file.
        var byType = new LinkedHashMap<String, List<ValidationError>>();
        for (var e : fileErrors) {
            if (e.coordinate() == null) continue;
            String typeName = typeOf(e.coordinate());
            byType.computeIfAbsent(typeName, k -> new java.util.ArrayList<>()).add(e);
        }
        for (var typeEntry : byType.entrySet()) {
            renderType(sb, typeEntry.getKey(), typeEntry.getValue());
        }
    }

    private static void renderType(StringBuilder sb, String typeName, List<ValidationError> typeErrors) {
        sb.append(GROUP_INDENT).append("type ").append(typeName).append('\n');

        // Errors whose coordinate IS the type (no dot) are type-level; they come first, sorted by line.
        var typeLevel = typeErrors.stream()
            .filter(e -> isTypeLevel(e.coordinate()))
            .sorted(byLineCol())
            .toList();
        typeLevel.forEach(e -> appendLeaf(sb, TYPE_LEAF_INDENT, e));

        // Field-level: group by full Type.field coordinate, preserving first-seen field order.
        var byField = new LinkedHashMap<String, List<ValidationError>>();
        for (var e : typeErrors) {
            if (isTypeLevel(e.coordinate())) continue;
            byField.computeIfAbsent(e.coordinate(), k -> new java.util.ArrayList<>()).add(e);
        }
        for (var fieldEntry : byField.entrySet()) {
            renderField(sb, fieldEntry.getKey(), fieldEntry.getValue());
        }
    }

    private static void renderField(StringBuilder sb, String coordinate, List<ValidationError> fieldErrors) {
        sb.append(FIELD_INDENT).append(coordinate);
        if (fieldErrors.size() > 1) {
            sb.append("  (").append(fieldErrors.size()).append(" errors)");
        }
        sb.append('\n');
        fieldErrors.stream().sorted(byLineCol()).forEach(e -> appendLeaf(sb, LEAF_INDENT, e));
    }

    private static void appendLeaf(StringBuilder sb, String indent, ValidationError e) {
        sb.append(indent);
        if (e.location() != null) {
            sb.append(e.location().getLine()).append(':').append(e.location().getColumn()).append("  ");
        } else {
            sb.append("?:?  ");
        }
        sb.append('[').append(e.kind().displayName()).append("]  ").append(e.message()).append('\n');
    }

    private static String summary(List<ValidationError> errors) {
        var counts = new EnumMap<RejectionKind, Integer>(RejectionKind.class);
        for (var e : errors) {
            counts.merge(e.kind(), 1, Integer::sum);
        }
        String breakdown = counts.entrySet().stream()
            .sorted(Comparator.comparing(en -> en.getKey().name()))
            .map(en -> en.getValue() + " " + en.getKey().displayName())
            .collect(Collectors.joining(", "));
        return errors.size() + " error(s): " + breakdown;
    }

    private static String delta(List<ValidationError> currentErrors, Set<DeltaKey> previous) {
        var current = keysOf(currentErrors);
        long added = current.stream().filter(k -> !previous.contains(k)).count();
        long fixed = previous.stream().filter(k -> !current.contains(k)).count();
        long unchanged = current.size() - added;
        if (added == 0 && fixed == 0 && unchanged == 0) {
            return "";
        }
        return "+" + added + " new, -" + fixed + " fixed, " + unchanged + " unchanged";
    }

    private static Map<String, List<ValidationError>> groupByFile(List<ValidationError> errors) {
        // TreeMap so files render in stable alphabetical order; "(unknown)" sorts last because of
        // the explicit bracket prefix.
        var byFile = new TreeMap<String, List<ValidationError>>();
        for (var e : errors) {
            String src = e.location() != null && e.location().getSourceName() != null
                ? e.location().getSourceName()
                : "(unknown source)";
            byFile.computeIfAbsent(src, k -> new java.util.ArrayList<>()).add(e);
        }
        return byFile;
    }

    private static Comparator<ValidationError> byLineCol() {
        return Comparator
            .comparingInt((ValidationError e) -> e.location() != null ? e.location().getLine() : Integer.MAX_VALUE)
            .thenComparingInt(e -> e.location() != null ? e.location().getColumn() : Integer.MAX_VALUE)
            .thenComparing(ValidationError::message);
    }

    private static boolean isTypeLevel(String coordinate) {
        return coordinate != null && coordinate.indexOf('.') < 0;
    }

    private static String typeOf(String coordinate) {
        int dot = coordinate.indexOf('.');
        return dot < 0 ? coordinate : coordinate.substring(0, dot);
    }

    /**
     * Stable identity for a {@link ValidationError} across watch cycles. Excludes line and column
     * so re-flowing the file (adding a comment, reformatting) does not invalidate every key.
     */
    public record DeltaKey(String file, String coordinate, RejectionKind kind, String message) {
        public static DeltaKey of(ValidationError e) {
            String file = e.location() != null && e.location().getSourceName() != null
                ? e.location().getSourceName()
                : "(unknown)";
            return new DeltaKey(file, e.coordinate(), e.kind(), e.message());
        }
    }
}
