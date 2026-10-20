package no.sikt.graphitron.lsp.hover;

import no.sikt.graphitron.lsp.facts.TypeInformation;

import java.util.ArrayList;
import java.util.stream.Collectors;

import static no.sikt.graphitron.lsp.hover.FieldInformationMarkdown.directive;
import static no.sikt.graphitron.lsp.hover.FieldInformationMarkdown.line;

/**
 * The type hover's text, on {@link FieldInformationMarkdown}'s terms: a labelled line per fact the
 * store states about the type, and no classification.
 */
final class TypeInformationMarkdown {

    private TypeInformationMarkdown() {}

    static String render(String typeName, TypeInformation.Information info) {
        var sb = new StringBuilder("`").append(keyword(info.signature().kind())).append(" ")
            .append(typeName).append("`");
        var description = info.signature().description();
        if (description != null && !description.isBlank()) sb.append("\n\n").append(description.strip());
        var lines = new ArrayList<String>();
        for (var row : info.rows()) {
            var held = new StringBuilder("a row of `").append(row.schema()).append(".").append(row.table()).append("`");
            if (row.description() != null && !row.description().isBlank()) {
                held.append(" · ").append(row.description().strip());
            }
            lines.add(line("Holds", held.toString()));
        }
        for (var cls : info.classes()) {
            lines.add(line("Holds", "an instance of `" + cls + "`"));
        }
        // An object with no class of its own is a carrier graphitron builds: a connection or a payload.
        if (info.shapes().contains("OBJECT") && info.classes().isEmpty()) {
            lines.add(line("Holds", "an object graphitron builds"));
        }
        for (var node : info.nodes()) {
            lines.add(line("Node", "id `" + node.typeId() + "`"
                + (node.keyColumns().isEmpty() ? "" : " · keys `" + String.join(", ", node.keyColumns()) + "`")));
        }
        for (var handler : info.handlers()) {
            lines.add(line("Handles", "`" + handler.handler()
                + (handler.className() == null ? "" : " " + handler.className()) + "`"));
        }
        for (var added : info.added()) {
            lines.add(line("Added", "`" + directive(added) + "` (" + added.origin().toLowerCase() + ")"));
        }
        if (!info.defects().isEmpty()) {
            lines.add(line("Defects", info.defects().stream().map(code -> "`" + code + "`")
                .collect(Collectors.joining(", "))));
        }
        for (var l : lines) sb.append("\n\n").append(l);
        return sb.toString();
    }

    /** The SDL keyword that declares a type of this kind. */
    private static String keyword(String kind) {
        return switch (kind) {
            case "OBJECT" -> "type";
            case "INTERFACE" -> "interface";
            case "UNION" -> "union";
            case "ENUM" -> "enum";
            case "INPUT_OBJECT" -> "input";
            case "SCALAR" -> "scalar";
            default -> kind.toLowerCase();
        };
    }
}
