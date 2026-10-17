package no.sikt.graphitron.model.capture.federation;

import com.apollographql.federation.graphqljava.FederationDirectives;
import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import com.apollographql.federation.graphqljava.exceptions.UnsupportedLinkImportException;
import graphql.language.DescribedNode;
import graphql.language.AstPrinter;
import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.EnumTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NamedNode;
import graphql.language.NonNullType;
import graphql.language.ScalarTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.schema.federation.FederationSpec;
import no.sikt.graphitron.model.sink.Progress;
import org.jooq.DSLContext;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_DEFINITION;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.FEDERATION_VERSION_IMPORT;
import static no.sikt.graphitron.model.Tables.FEDERATION_WRITTEN_VERSION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The federation gatherer: the specification versions federation-jvm supports, what each brings
 * into a schema that links it, and what each lets a link import, read from the library itself so
 * nothing graphitron maintains lists federation's vocabulary or its namespacing.
 *
 * <p>The library is the source, named by its group and artifact and stamped by the jar its classes
 * were loaded from. The jar carries no Maven metadata to name its release by, so its content is its
 * identity: a stamp the store already holds is not read again, and a changed one is read whole,
 * the old rows going first. Where the classes come from no jar the stamp is null, which reads as
 * changed, on {@code store_source}'s terms.
 *
 * <p>Beside them, the version graphitron writes when it adds a link of its own, from
 * {@link FederationSpec#URL}: graphitron's choice rather than the library's, recorded under the
 * library so the database refuses a version the library does not support.
 *
 * <p>Both readings go through the library's public API. The versions are probed upward from v2.0
 * until it refuses one. A version's imports are the names its definitions carry, each tried as an
 * import, since several versions share one definition file and the library refuses a directive
 * younger than the version a link names.
 */
public final class FederationCapture {

    private FederationCapture() {}

    /** This gatherer's row in {@code store_graph_progress}. */
    private static final String GATHERER = "federation";

    /** The source kind a library graphitron itself depends on is registered under. */
    private static final String LIBRARY = "LIBRARY";

    /** The library's source name: its group and artifact, which is what stays put across releases. */
    public static final String SOURCE_NAME =
        "com.apollographql.federation:federation-graphql-java-support";

    /**
     * Makes {@code graphName} claim the federation library on its classpath, reading the library's
     * versions and imports where the store does not hold them for the jar now loaded, in one
     * transaction.
     */
    public static void capture(DSLContext dsl, String graphName, LocalDateTime readAt) {
        Progress.started(dsl, graphName, GATHERER);
        dsl.transaction(tx -> gather(tx.dsl(), graphName, stamp(),
            FederationSpec.URL.substring(FederationSpec.SPEC_PREFIX.length()), readAt));
        Progress.completed(dsl, graphName, GATHERER);
    }

    static void gather(DSLContext dsl, String graphName, String stamp, String writtenVersion,
                       LocalDateTime readAt) {
        var s = STORE_SOURCE;
        String held = dsl.select(s.STAMP).from(s).where(s.SOURCE_NAME.eq(SOURCE_NAME)).fetchOne(s.STAMP);
        dsl.insertInto(s, s.SOURCE_NAME, s.SOURCE_KIND, s.LAST_SEEN)
            .values(SOURCE_NAME, LIBRARY, readAt)
            .onDuplicateKeyUpdate()
            .set(s.LAST_SEEN, readAt)
            .execute();
        var m = STORE_GRAPH_SOURCE;
        dsl.insertInto(m, m.GRAPH_NAME, m.SOURCE_NAME, m.READ_AT)
            .values(graphName, SOURCE_NAME, readAt)
            .onDuplicateKeyIgnore()
            .execute();

        if (stamp == null || !stamp.equals(held)) {
            read(dsl, stamp, readAt);
        }
        // Every reading, the library unchanged or not: a graphitron upgrade can change the version
        // it writes without the library changing at all.
        var w = FEDERATION_WRITTEN_VERSION;
        dsl.insertInto(w, w.SOURCE_NAME, w.VERSION)
            .values(SOURCE_NAME, writtenVersion)
            .onDuplicateKeyUpdate()
            .set(w.VERSION, writtenVersion)
            .execute();
    }

    /** Reads the library whole, the old rows going first, and stamps it once the new rows are in. */
    private static void read(DSLContext dsl, String stamp, LocalDateTime readAt) {
        var s = STORE_SOURCE;
        // The old versions go first, and everything under them by cascade.
        var v = FEDERATION_VERSION;
        dsl.deleteFrom(v).where(v.SOURCE_NAME.eq(SOURCE_NAME)).execute();
        for (var version : versions()) {
            dsl.insertInto(v, v.SOURCE_NAME, v.VERSION).values(SOURCE_NAME, version.version()).execute();
            for (var definition : version.definitions()) {
                write(dsl, version.version(), definition);
            }
        }
        dsl.update(s)
            .set(s.STAMP, stamp)
            .set(s.READ_AT, readAt)
            .where(s.SOURCE_NAME.eq(SOURCE_NAME))
            .execute();
    }

    private static void write(DSLContext dsl, String version, Definition definition) {
        var d = FEDERATION_VERSION_DEFINITION;
        dsl.insertInto(d, d.SOURCE_NAME, d.VERSION, d.NAME, d.KIND, d.UNIMPORTED_NAME, d.REPEATABLE,
                d.DESCRIPTION)
            .values(SOURCE_NAME, version, definition.name(), definition.kind(),
                definition.unimportedName(), definition.repeatable(), definition.description())
            .execute();
        var a = FEDERATION_VERSION_DIRECTIVE_ARGUMENT;
        for (var argument : definition.arguments()) {
            dsl.insertInto(a, a.SOURCE_NAME, a.VERSION, a.NAME, a.ARGUMENT_NAME, a.ORDINAL, a.TYPE_SDL,
                    a.NAMED_TYPE, a.NON_NULL, a.IS_LIST, a.ITEM_NON_NULL, a.DEFAULT_VALUE_SDL,
                    a.DESCRIPTION)
                .values(SOURCE_NAME, version, definition.name(), argument.name(), argument.ordinal(),
                    argument.typeSdl(), argument.namedType(), argument.nonNull(), argument.isList(),
                    argument.itemNonNull(), argument.defaultValueSdl(), argument.description())
                .execute();
        }
        var l = FEDERATION_VERSION_DIRECTIVE_LOCATION;
        for (String location : definition.locations()) {
            dsl.insertInto(l, l.SOURCE_NAME, l.VERSION, l.NAME, l.LOCATION)
                .values(SOURCE_NAME, version, definition.name(), location)
                .execute();
        }
        if (definition.importable()) {
            var i = FEDERATION_VERSION_IMPORT;
            dsl.insertInto(i, i.SOURCE_NAME, i.VERSION, i.NAME)
                .values(SOURCE_NAME, version, definition.name())
                .execute();
        }
    }

    /** One version the library supports, and what it brings into a schema that links it. */
    record Version(String version, List<Definition> definitions) {}

    /** One definition, under the specification's name and the one the library gives it unimported. */
    record Definition(String name, String kind, String unimportedName, Boolean repeatable,
                      String description, List<Argument> arguments, List<String> locations,
                      boolean importable) {}

    /** One argument of a directive, its type under the specification's names. */
    record Argument(String name, int ordinal, String typeSdl, String namedType, boolean nonNull,
                    boolean isList, Boolean itemNonNull, String defaultValueSdl, String description) {}

    /** Every version the library supports, in order, with what each brings in. */
    static List<Version> versions() {
        var versions = new ArrayList<Version>();
        for (int minor = 0; ; minor++) {
            String version = "v2." + minor;
            // The library's lists are raw, so each element is narrowed where it is read.
            List<?> definitions;
            try {
                definitions = FederationDirectives.loadFederationSpecDefinitions(
                    FederationSpec.SPEC_PREFIX + version);
            } catch (RuntimeException refused) {
                return versions;
            }
            // The library renames each definition 1:1 and in order for a link importing nothing,
            // which is the name it gives a definition no import names.
            List<?> unimported = LinkDirectiveProcessor.loadFederationImportedDefinitions(
                link(version, List.of())).toList();
            if (unimported.size() != definitions.size()) {
                throw new IllegalStateException("federation-graphql-java-support renamed "
                    + definitions.size() + " definitions of " + version + " into "
                    + unimported.size());
            }
            var read = new ArrayList<Definition>();
            var seen = new HashSet<String>();
            for (int at = 0; at < definitions.size(); at++) {
                // The v2.6 and v2.7 definition files declare @tag twice; the first is the one a
                // registry keeps, the second being refused.
                if (seen.add(spelled(definitions.get(at)))) {
                    read.add(definition(version, definitions.get(at), unimported.get(at)));
                }
            }
            versions.add(new Version(version, List.copyOf(read)));
        }
    }

    private static Definition definition(String version, Object definition, Object unimported) {
        String name = spelled(definition);
        String kind = switch (definition) {
            case DirectiveDefinition ignored -> "DIRECTIVE";
            case ScalarTypeDefinition ignored -> "SCALAR";
            case EnumTypeDefinition ignored -> "ENUM";
            default -> throw new IllegalStateException("federation " + version + " defines " + name
                + ", a " + definition.getClass().getSimpleName() + ", which graphitron does not model");
        };
        var arguments = new ArrayList<Argument>();
        var locations = new ArrayList<String>();
        Boolean repeatable = null;
        if (definition instanceof DirectiveDefinition directive) {
            repeatable = directive.isRepeatable();
            int ordinal = 0;
            for (var input : directive.getInputValueDefinitions()) {
                arguments.add(argument(input, ordinal++));
            }
            directive.getDirectiveLocations().forEach(location -> locations.add(location.getName()));
        }
        return new Definition(name, kind, spelled(unimported), repeatable,
            description(((DescribedNode<?>) definition).getDescription()),
            List.copyOf(arguments), List.copyOf(locations), importable(version, name));
    }

    private static Argument argument(InputValueDefinition input, int ordinal) {
        Type<?> type = input.getType();
        boolean nonNull = type instanceof NonNullType;
        Type<?> outer = nonNull ? ((NonNullType) type).getType() : type;
        boolean isList = outer instanceof ListType;
        Boolean itemNonNull = null;
        if (isList) {
            Type<?> item = ((ListType) outer).getType();
            while (item instanceof ListType || (item instanceof NonNullType wrapped
                    && wrapped.getType() instanceof ListType)) {
                item = item instanceof ListType list ? list.getType()
                    : ((NonNullType) item).getType();
            }
            itemNonNull = item instanceof NonNullType;
        }
        Type<?> named = type;
        while (!(named instanceof TypeName)) {
            named = named instanceof NonNullType wrapped ? wrapped.getType() : ((ListType) named).getType();
        }
        return new Argument(input.getName(), ordinal, AstPrinter.printAstCompact(type),
            ((TypeName) named).getName(), nonNull, isList, itemNonNull,
            input.getDefaultValue() == null ? null : AstPrinter.printAstCompact(input.getDefaultValue()),
            description(input.getDescription()));
    }

    private static String spelled(Object definition) {
        return definition instanceof DirectiveDefinition directive
            ? "@" + directive.getName() : ((NamedNode<?>) definition).getName();
    }

    private static String description(Description description) {
        return description == null ? null : description.getContent();
    }

    /** Whether a link naming {@code version} may import {@code name}, as the library answers it. */
    private static boolean importable(String version, String name) {
        try {
            LinkDirectiveProcessor.loadFederationImportedDefinitions(link(version, List.of(name)))
                .toList();
            return true;
        } catch (UnsupportedLinkImportException refused) {
            return false;
        }
    }

    /** A schema holding only a federation link to {@code version} importing {@code names}. */
    private static TypeDefinitionRegistry link(String version, List<String> names) {
        var imports = names.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", "));
        return new SchemaParser().parse("extend schema @link(url: \""
            + FederationSpec.SPEC_PREFIX + version + "\", import: [" + imports + "])");
    }

    /**
     * The content of the jar the library's classes were loaded from, or null where they came from
     * something that is not a regular file to hash.
     */
    static String stamp() {
        try {
            var source = FederationDirectives.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path jar = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(jar) ? SourceStamp.ofFile(jar) : null;
        } catch (URISyntaxException | RuntimeException unlocatable) {
            return null;
        }
    }
}
