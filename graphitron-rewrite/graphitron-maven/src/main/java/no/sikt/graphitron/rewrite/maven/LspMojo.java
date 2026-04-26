package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.lsp.server.Launcher;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Launches the graphitron LSP server over stdio.
 *
 * <p>Invocation: {@code mvn graphitron-rewrite:lsp}. Spike scope: thin
 * wrapper around {@link Launcher#main(String[])}. The Mojo lives in this
 * module because the umbrella roadmap entry calls for the Maven plugin
 * to be the user-facing launcher. The implementation lives in
 * {@code graphitron-lsp} and carries its own tests.
 */
@Mojo(
    name = "lsp",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class LspMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Launcher.main(new String[0]);
        } catch (Exception e) {
            throw new MojoExecutionException("LSP server failed", e);
        }
    }
}
