package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.Table;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY;

/**
 * The build errors for {@code @outbox}: every {@code OUTBOX_*} defect the store holds, each at the
 * application it affects. The verdict is the stored {@code graphitron_entry_defect} row, which the
 * editor reads through the {@code diagnostic} view; what this class adds is the population the
 * build asks about and the message, which is the code's statement with the row's detail.
 *
 * <p>Every relation it names is a table, so it expands no rule on a read. A defect is always at an
 * {@code @outbox} application, including the ones that fault the configuration, which has no SDL
 * position of its own: each application the fault affects carries its own row, and the key is the
 * application and the code.
 */
public final class OutboxDefects {

    private OutboxDefects() {}

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS =
        Set.of(GRAPHITRON_ENTRY_DEFECT, GRAPHITRON_DEFECT_TYPE, GRAPHITRON_OUTBOX_ENTRY);

    /** One defect of one {@code @outbox}, with what its message needs. */
    public record Row(String typeName, String fieldName, int ordinal, String code,
                      String statement, String detail, SourceLocation location) {

        /** The coordinate the minted error attaches to. */
        public String coordinate() {
            return typeName + "." + fieldName;
        }
    }

    /** The detection pass's typed product, the rows kept beside the violations they mint. */
    public record Detection(List<Row> rows) {

        public Detection {
            rows = List.copyOf(rows);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }

        /** Every violation, in coordinate order. */
        public List<ValidationError> violations() {
            return rows.stream()
                .map(r -> ValidationError.forField(r.coordinate(), rejectionOf(r), r.location()))
                .toList();
        }
    }

    /** The {@code OUTBOX_*} defect rows over {@code graphName}'s partition. */
    public static Detection detect(org.jooq.DSLContext dsl, String graphName) {
        var d = GRAPHITRON_ENTRY_DEFECT;
        var t = GRAPHITRON_DEFECT_TYPE;
        var o = GRAPHITRON_OUTBOX_ENTRY;
        var rows = dsl
            .select(o.TYPE_NAME, o.FIELD_NAME, o.ORDINAL, d.CODE, t.STATEMENT, d.DETAIL,
                d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN)
            .from(d)
            .join(t).on(t.CODE.eq(d.CODE))
            .join(o).on(o.GRAPH_NAME.eq(d.GRAPH_NAME), o.SOURCE_NAME.eq(d.SOURCE_NAME),
                o.SOURCE_LINE.eq(d.SOURCE_LINE), o.SOURCE_COLUMN.eq(d.SOURCE_COLUMN))
            .where(d.GRAPH_NAME.eq(graphName), d.CODE.like("OUTBOX\\_%"))
            .fetch(r -> new Row(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(),
                r.value6(), location(r.value7(), r.value8(), r.value9())));
        return new Detection(rows.stream()
            .sorted(Comparator.comparing(Row::typeName).thenComparing(Row::fieldName)
                .thenComparing(Row::ordinal).thenComparing(Row::code))
            .toList());
    }

    /** The one mint of this defect's {@link Rejection}: an author error. */
    public static Rejection rejectionOf(Row row) {
        return Rejection.structural(row.detail() == null
            ? row.statement()
            : row.statement() + " (" + row.detail() + ")");
    }

    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
