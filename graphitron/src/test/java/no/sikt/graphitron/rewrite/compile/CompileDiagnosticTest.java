package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.net.URI;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.compile.CompileDiagnostic;

/**
 * The flattening's own pins: the path form shared with the schema channel and with every
 * {@code file} column in the store, javac's sentinel transcription, and the severity projection's
 * totality over {@link Diagnostic.Kind}.
 */
@UnitTier
class CompileDiagnosticTest {

    @Test
    @DisplayName("the flattening keeps javac's own path, the form every file column stores")
    void fileSpellingAgreesWithTheSchemaChannels() {
        String path = "/work/target/generated-sources/graphitron/gen/pkg/FilmFetchers.java";
        var flattened = CompileDiagnostic.from(diagnostic(path, 12, 7, Diagnostic.Kind.ERROR,
            "compiler.err.cant.resolve"));
        assertThat(flattened.file()).isEqualTo(path);
    }

    @Test
    @DisplayName("a diagnostic with no source keeps the placeholder, and NOPOS stays -1")
    void sentinelsTranscribeAsJavacsOwnValues() {
        var flattened = CompileDiagnostic.from(diagnostic(null, Diagnostic.NOPOS, Diagnostic.NOPOS,
            Diagnostic.Kind.WARNING, null));
        assertThat(flattened.file()).isEqualTo("(no source)");
        assertThat(flattened.line()).isEqualTo(-1);
        assertThat(flattened.column()).isEqualTo(-1);
        assertThat(flattened.code()).isNull();
    }

    @Test
    @DisplayName("the code passes through typed, kind verbatim")
    void codeAndKindTranscribeVerbatim() {
        var flattened = CompileDiagnostic.from(diagnostic("/gen/A.java", 1, 1,
            Diagnostic.Kind.MANDATORY_WARNING, "compiler.warn.unchecked"));
        assertThat(flattened.kind()).isEqualTo("MANDATORY_WARNING");
        assertThat(flattened.code()).isEqualTo("compiler.warn.unchecked");
    }

    /**
     * The severity projection's partition pin, in two halves. The loop states the projection over
     * every kind the enum has (every kind lands in one of the two severities, {@code ERROR} alone
     * in {@code "error"}), but against a ternary with a catch-all it is true for any constant, so
     * on its own it can never fail. The golden set beside it is the half that actually fires when
     * javac grows a {@code Kind}: the enum must contain exactly the kinds classified today, so a
     * new one fails a build here instead of falling through to {@code "warning"} silently.
     */
    @Test
    @DisplayName("the severity projection partitions every javac kind, and the kind set is pinned")
    void severityProjectionIsTotalOverJavacsKinds() {
        assertThat(Diagnostic.Kind.values())
            .as("javac grew a Diagnostic.Kind this projection has never classified; decide its "
                + "severity in CompileDiagnostic.severity() before widening this pin")
            .containsExactlyInAnyOrder(Diagnostic.Kind.ERROR, Diagnostic.Kind.WARNING,
                Diagnostic.Kind.MANDATORY_WARNING, Diagnostic.Kind.NOTE, Diagnostic.Kind.OTHER);
        for (Diagnostic.Kind kind : Diagnostic.Kind.values()) {
            var diagnostic = new CompileDiagnostic("/gen/A.java", 1, 1, kind.name(), null, "m");
            assertThat(diagnostic.severity())
                .as("severity of kind %s", kind)
                .isIn("error", "warning");
            assertThat(diagnostic.severity())
                .as("only ERROR projects to error (kind %s)", kind)
                .isEqualTo(kind == Diagnostic.Kind.ERROR ? "error" : "warning");
        }
    }

    private static Diagnostic<JavaFileObject> diagnostic(String path, long line, long column,
                                                         Diagnostic.Kind kind, String code) {
        JavaFileObject source = path == null ? null : new SimpleJavaFileObject(
            URI.create("file://" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public String getName() {
                return path;
            }
        };
        return new Diagnostic<>() {
            @Override
            public Kind getKind() {
                return kind;
            }

            @Override
            public JavaFileObject getSource() {
                return source;
            }

            @Override
            public long getPosition() {
                return Diagnostic.NOPOS;
            }

            @Override
            public long getStartPosition() {
                return Diagnostic.NOPOS;
            }

            @Override
            public long getEndPosition() {
                return Diagnostic.NOPOS;
            }

            @Override
            public long getLineNumber() {
                return line;
            }

            @Override
            public long getColumnNumber() {
                return column;
            }

            @Override
            public String getCode() {
                return code;
            }

            @Override
            public String getMessage(Locale locale) {
                return "cannot find symbol";
            }
        };
    }
}
