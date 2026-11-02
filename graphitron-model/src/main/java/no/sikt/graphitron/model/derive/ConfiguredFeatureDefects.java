package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;

/**
 * The build errors for a configured {@code <schemaInput><feature>} the schema's {@code @feature}
 * definition cannot take: every {@code FEATURE_*} defect the store holds. The verdict is the stored
 * {@code graphitron_entry_defect} row, one per element the configuration would have flagged, which
 * the editor reads through the {@code diagnostic} view; what this class adds is the population the
 * build asks about and the message, which is the code's statement with the row's detail.
 *
 * <p>The configuration has no SDL position, so each element it reaches carries its own row, as
 * {@link OutboxDefects}' configuration faults do. A schema declaring no {@code @feature} at all
 * refuses every element a feature reaches, which on a consumer schema is thousands, so the build
 * sees one error per code and detail, at the first element in position order, saying how many more
 * there are. The store keeps every row, which is what the editor underlines.
 *
 * <p>Every relation it names is a table, so it expands no rule on a read.
 */
public final class ConfiguredFeatureDefects {

    private ConfiguredFeatureDefects() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS =
        Set.of(GRAPHITRON_ENTRY_DEFECT, GRAPHITRON_DEFECT_TYPE, GRAPHQL_AST_ELEMENT_ENTRY);

    /** One refused landing: the element it would have flagged, and what its message needs. */
    public record Row(String coordinate, String code, String statement, String detail,
                      SourceLocation location) {}

    /**
     * The refusals sharing one code and one detail, which is one fault of the configuration and
     * the definition: the first in position order, and how many more there are.
     */
    public record Group(Row first, int more, int files) {

        /** The one error the build shows for the group. */
        public ValidationError violation() {
            var detail = first.detail() == null ? "" : first.detail();
            if (more > 0) {
                detail += (detail.isEmpty() ? "" : "; ") + "and " + more + " more element"
                    + (more == 1 ? "" : "s") + " in " + files + " file" + (files == 1 ? "" : "s");
            }
            var message = detail.isEmpty()
                ? first.statement()
                : first.statement() + " (" + detail + ")";
            return new ValidationError(first.coordinate(),
                Rejection.structural(message).prefixedWith("Element '" + first.coordinate() + "': "),
                first.location());
        }
    }

    /** The detection pass's typed product: every refused landing, and the groups the build reports. */
    public record Detection(List<Row> rows) {

        public Detection {
            rows = List.copyOf(rows);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** The rows collapsed by code and detail, each group at its first row in position order. */
        public List<Group> groups() {
            var byFault = new LinkedHashMap<List<String>, List<Row>>();
            for (Row row : rows) {
                byFault.computeIfAbsent(List.of(row.code(), String.valueOf(row.detail())),
                    k -> new ArrayList<>()).add(row);
            }
            var groups = new ArrayList<Group>();
            byFault.values().forEach(group -> {
                var files = new LinkedHashSet<String>();
                group.forEach(r -> files.add(r.location().getSourceName()));
                groups.add(new Group(group.getFirst(), group.size() - 1, files.size()));
            });
            return List.copyOf(groups);
        }

        /** One violation per group, in the order the groups' first rows sort. */
        public List<ValidationError> violations() {
            return groups().stream().map(Group::violation).toList();
        }
    }

    /** The {@code FEATURE_*} defect rows over {@code graphName}'s partition, in position order. */
    public static Detection detect(DSLContext dsl, String graphName) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var t = GRAPHITRON_DEFECT_TYPE;
        var e = GRAPHQL_AST_ELEMENT_ENTRY;
        var rows = dsl
            .select(e.COORDINATE, d.CODE, t.STATEMENT, d.DETAIL,
                d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN)
            .from(d)
            .join(t).on(t.CODE.eq(d.CODE))
            .join(e).on(e.GRAPH_NAME.eq(d.GRAPH_NAME), e.SOURCE_NAME.eq(d.SOURCE_NAME),
                e.SOURCE_LINE.eq(d.SOURCE_LINE), e.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .where(d.GRAPH_NAME.eq(graphName), d.CODE.like("FEATURE\\_%"))
            .fetch(r -> new Row(r.value1(), r.value2(), r.value3(), r.value4(),
                new SourceLocation(r.value6(), r.value7(), r.value5())));
        return new Detection(rows.stream()
            .sorted(Comparator.comparing((Row r) -> r.location().getSourceName())
                .thenComparingInt(r -> r.location().getLine())
                .thenComparingInt(r -> r.location().getColumn())
                .thenComparing(Row::code))
            .toList());
    }
}
