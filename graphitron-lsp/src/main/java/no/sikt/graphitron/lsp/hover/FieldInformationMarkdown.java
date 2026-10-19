package no.sikt.graphitron.lsp.hover;

import no.sikt.graphitron.lsp.facts.FieldInformation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The field hover's text: one labelled line per fact the store states, in the words an author uses,
 * and no classification. Each line is written only where its fact is there, except the column of a
 * scalar or enum read from a table, whose absence is itself the answer.
 */
final class FieldInformationMarkdown {

    private FieldInformationMarkdown() {}

    static String render(String coordinate, FieldInformation.Information info) {
        var sb = new StringBuilder();
        sb.append("`").append(coordinate);
        if (info.signature() != null) sb.append(": ").append(info.signature().typeSdl());
        sb.append("`");
        if (info.signature() != null && info.signature().description() != null
            && !info.signature().description().isBlank()) {
            sb.append("\n\n").append(info.signature().description().strip());
        }
        var lines = new ArrayList<String>();
        value(info, lines);
        for (var source : info.sources()) {
            lines.add(line("Source", "`" + source.table() + "`, " + basis(source.basis(), coordinate)));
        }
        for (var node : info.nodes()) {
            lines.add(line("Node", "`" + info.target().typeName() + "` · id `" + node.typeId() + "`"
                + (node.keyColumns().isEmpty() ? "" : " · keys `" + String.join(", ", node.keyColumns()) + "`")));
        }
        for (var method : info.methods()) {
            lines.add(line("Calls", "`@" + method.directive() + "` " + "`" + methodSignature(method) + "`"));
        }
        for (var routine : info.routines()) {
            lines.add(line("Calls", "`@routine` `" + routine + "`"));
        }
        for (var added : info.added()) {
            lines.add(line("Added", "`" + directive(added) + "` (" + added.origin().toLowerCase() + ")"));
        }
        if (info.pathElements() > 0) {
            lines.add(line("Path", info.pathElements() + (info.pathElements() == 1 ? " element" : " elements")));
        }
        if (!info.defects().isEmpty()) {
            lines.add(line("Defects", info.defects().stream().map(code -> "`" + code + "`")
                .collect(Collectors.joining(", "))));
        }
        for (var l : lines) sb.append("\n\n").append(l);
        return sb.toString();
    }

    /** What the value is: the column a scalar reads, or the type an object field returns and where it is bound. */
    private static void value(FieldInformation.Information info, List<String> lines) {
        var target = info.target();
        if (target == null) return;
        if ("SCALAR".equals(target.kind()) || "ENUM".equals(target.kind())) {
            // A scalar with no source table reads its source object, which is no column to name.
            if (info.columns().isEmpty() && !info.sources().isEmpty()) {
                lines.add(line("Column", "no column"));
            }
            for (var column : info.columns()) {
                var sb = new StringBuilder("`").append(column.table()).append(".").append(column.column())
                    .append("` ").append(column.sqlType().toLowerCase());
                if (!column.nullable()) sb.append(" not null");
                if ("JOOQ_NAME".equals(column.matchedBy())) sb.append(", matched by jOOQ name");
                if (column.description() != null && !column.description().isBlank()) {
                    sb.append(" · ").append(column.description().strip());
                }
                lines.add(line("Column", sb.toString()));
            }
            return;
        }
        var sb = new StringBuilder("`").append(target.typeName()).append("`");
        if (target.wrapperTypeName() != null) sb.append(" in `").append(target.wrapperTypeName()).append("`");
        var tables = info.targetTables().stream()
            .map(t -> (t.typeName().equals(target.typeName()) ? "" : "`" + t.typeName() + "` ")
                + "`" + t.schema() + "." + t.table() + "`")
            .toList();
        if (!tables.isEmpty()) sb.append(" → ").append(String.join(", ", tables));
        lines.add(line("Target", sb.toString()));
    }

    private static String basis(String basis, String coordinate) {
        String type = coordinate.substring(0, coordinate.indexOf('.'));
        return switch (basis) {
            case "SOURCE_TYPE" -> "the parent type's table";
            case "INHERITED" -> "inherited through `" + type + "`, which has no table of its own";
            case "RECORD_CLASS" -> "the table whose record class backs `" + type + "`";
            case "SOURCE_ROW" -> "the table of the key its `@sourceRow` lifter returns";
            default -> basis;
        };
    }

    private static String directive(FieldInformation.Added added) {
        if (added.arguments().isEmpty()) return "@" + added.directive();
        return "@" + added.directive() + "(" + added.arguments().stream()
            .map(a -> a.name() + ": " + a.valueSdl())
            .collect(Collectors.joining(", ")) + ")";
    }

    /** {@code FilmService.topRated(DSLContext, int)}, from the class and the JVM descriptor. */
    static String methodSignature(FieldInformation.Method method) {
        String cls = method.className().substring(method.className().lastIndexOf('.') + 1);
        return cls + "." + method.methodName() + "(" + String.join(", ", parameters(method.descriptor())) + ")";
    }

    private static List<String> parameters(String descriptor) {
        var out = new ArrayList<String>();
        int i = descriptor.indexOf('(') + 1;
        int end = descriptor.indexOf(')');
        while (i > 0 && i < end) {
            int dims = 0;
            while (descriptor.charAt(i) == '[') { dims++; i++; }
            String name;
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                String fqn = descriptor.substring(i + 1, semi);
                name = fqn.substring(fqn.lastIndexOf('/') + 1).replace('$', '.');
                i = semi + 1;
            } else {
                name = switch (c) {
                    case 'Z' -> "boolean"; case 'B' -> "byte"; case 'C' -> "char"; case 'S' -> "short";
                    case 'I' -> "int"; case 'J' -> "long"; case 'F' -> "float"; case 'D' -> "double";
                    default -> String.valueOf(c);
                };
                i++;
            }
            out.add(name + "[]".repeat(dims));
        }
        return out;
    }

    private static String line(String label, String value) {
        return "**" + label + "** " + value;
    }
}
