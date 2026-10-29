package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SESSION_HANDLE_MEMBER;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SESSION_HANDLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The build errors for session identity: an {@code argMapping} entry reading {@code $session}
 * where the configuration cannot answer it, and a configured handle class the classpath does not
 * hold.
 *
 * <p>The first kind is five defect codes, each a stored {@code graphitron_entry_defect} row at the
 * written position of the mapping string, with one {@code graphitron_argmapping_pair_defect} row
 * under it for every entry that offends. Each offending entry is one error. What this class adds
 * is the coordinate the string sits on, from {@code graphitron_argmapping_site}, the members the
 * handle offers where a member is unknown, from {@code graphitron_session_handle_member}, and the
 * message.
 *
 * <p>The second kind has no written SDL position, which is the defect relation's key, so it is
 * read and not stored: a handle row with no class row under any source the graph reads. It mints
 * one schema-wide error and stores nothing.
 */
public final class SessionIdentityDefects {

    private SessionIdentityDefects() {}

    /** The defect codes this class reads. */
    public static final Set<String> CODES = Set.of(
        "SESSION_BINDING_WITHOUT_IDENTITY", "SESSION_HANDLE_UNNAMED", "SESSION_MEMBER_UNKNOWN",
        "SESSION_MEMBER_AMBIGUOUS", "SESSION_BINDING_TYPE_MISMATCH");

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(
        GRAPHITRON_ARGMAPPING_PAIR_DEFECT, GRAPHITRON_ARGMAPPING_SITE, GRAPHITRON_SESSION_HANDLE_MEMBER, STORE_GRAPH_SESSION_HANDLE,
        STORE_GRAPH_SOURCE, CODE_CLASS);

    /** One stored defect, with the coordinate its mapping string sits on and what its message needs. */
    public record Binding(String code, String detail, String coordinate, SourceLocation location) {}

    /** A configured handle class no classpath entry the graph reads holds as a public class. */
    public record UnresolvedHandle(String handleClass) {}

    /** The detection pass's typed product, the rows kept beside the violations they mint. */
    public record Detection(List<Binding> bindings, List<UnresolvedHandle> unresolved) {

        public Detection {
            bindings = List.copyOf(bindings);
            unresolved = List.copyOf(unresolved);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of(), List.of());
        }

        /** Every violation: the unresolved handle first, then the bindings in position order. */
        public List<ValidationError> violations() {
            var out = new java.util.ArrayList<ValidationError>();
            for (var u : unresolved) {
                out.add(new ValidationError("<schema>", rejectionOf(u), null));
            }
            for (var b : bindings) {
                out.add(ValidationError.forField(b.coordinate(), rejectionOf(b), b.location()));
            }
            return List.copyOf(out);
        }
    }

    /** The defect rows and the unresolved handle over {@code graphName}'s partition. */
    public static Detection detect(DSLContext dsl, String graphName) {
        var defect = GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
        var site = GRAPHITRON_ARGMAPPING_SITE;
        var offered = String.join(", ", dsl
            .selectDistinct(GRAPHITRON_SESSION_HANDLE_MEMBER.MEMBER_NAME)
            .from(GRAPHITRON_SESSION_HANDLE_MEMBER)
            .where(GRAPHITRON_SESSION_HANDLE_MEMBER.GRAPH_NAME.eq(graphName))
            .orderBy(GRAPHITRON_SESSION_HANDLE_MEMBER.MEMBER_NAME)
            .fetch(GRAPHITRON_SESSION_HANDLE_MEMBER.MEMBER_NAME));
        var bindings = dsl
            .select(defect.CODE, defect.DETAIL, site.COORDINATE, defect.SOURCE_NAME,
                defect.SOURCE_LINE, defect.SOURCE_COLUMN)
            .from(defect)
            .join(site).on(site.GRAPH_NAME.eq(defect.GRAPH_NAME),
                site.SOURCE_NAME.eq(defect.SOURCE_NAME), site.SOURCE_LINE.eq(defect.SOURCE_LINE),
                site.SOURCE_COLUMN.eq(defect.SOURCE_COLUMN))
            .where(defect.GRAPH_NAME.eq(graphName), defect.CODE.in(CODES))
            .fetch(r -> new Binding(r.value1(),
                "SESSION_MEMBER_UNKNOWN".equals(r.value1())
                    ? r.value2() + " (offered: " + (offered.isEmpty() ? "none" : offered) + ")"
                    : r.value2(),
                r.value3(), new SourceLocation(r.value5(), r.value6(), r.value4())));
        var sorted = bindings.stream()
            .sorted(Comparator.comparing(Binding::coordinate).thenComparing(Binding::code)
                .thenComparing(Binding::detail))
            .toList();

        var handle = STORE_GRAPH_SESSION_HANDLE;
        var source = STORE_GRAPH_SOURCE;
        var klass = CODE_CLASS;
        var unresolved = dsl.select(handle.HANDLE_CLASS)
            .from(handle)
            .where(handle.GRAPH_NAME.eq(graphName),
                notExists(selectOne().from(source)
                    .join(klass).on(klass.SOURCE_NAME.eq(source.SOURCE_NAME),
                        klass.CLASS_NAME.eq(handle.HANDLE_CLASS))
                    .where(source.GRAPH_NAME.eq(handle.GRAPH_NAME))))
            .fetch(r -> new UnresolvedHandle(r.value1()));
        return new Detection(sorted, unresolved);
    }

    /** The one mint of a stored session defect's {@link Rejection}: an author error. */
    public static Rejection rejectionOf(Binding row) {
        return Rejection.structural(switch (row.code()) {
            case "SESSION_BINDING_WITHOUT_IDENTITY" -> "argMapping reads $session (" + row.detail()
                + ") but no <sessionState> is configured; add <sessionState> to the build or bind "
                + "the parameter another way.";
            case "SESSION_HANDLE_UNNAMED" -> "argMapping reads $session (" + row.detail()
                + ") but <sessionState> names no <handle>; name the class the mount callback "
                + "returns in <handle>.";
            case "SESSION_MEMBER_UNKNOWN" -> "argMapping reads a member of $session that the "
                + "handle class does not offer: " + row.detail() + ".";
            case "SESSION_MEMBER_AMBIGUOUS" -> "argMapping reads a member of $session that two "
                + "accessors of the handle class offer (" + row.detail() + "); remove one accessor.";
            case "SESSION_BINDING_TYPE_MISMATCH" -> "argMapping binds $session to a parameter of "
                + "another type: " + row.detail() + ".";
            default -> throw new IllegalStateException("not a session identity defect: " + row.code());
        });
    }

    /** The one mint of the unresolved handle's {@link Rejection}: a configuration error. */
    public static Rejection rejectionOf(UnresolvedHandle row) {
        return Rejection.structural("<sessionState><handle> names '" + row.handleClass()
            + "', which no classpath entry the build declares holds as a public class; compile it "
            + "before generation (a module the build builds first) and name it by its fully "
            + "qualified name.");
    }
}
