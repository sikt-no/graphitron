package no.sikt.graphitron.jakarta.rest;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;

/**
 * The dependency-inversion seam between {@code graphitron-jakarta-rest} and a Graphitron subgraph.
 *
 * <p>The library cannot name the generated {@code Graphitron} facade: that class lives in a
 * per-subgraph package, and {@code newExecutionInput} varies per schema in both arity and parameter
 * type (its context-arg values are resolved per request from auth). The subgraph supplies a small
 * adapter implementing this interface; the adapter is the only place that names the facade, through
 * method references to it. The library depends on this interface, never on a generated symbol.
 *
 * <p>This is a consumer-implemented SPI, so it is a plain (open) interface, not a sealed hierarchy:
 * the set of implementations is open by design, owned by each consuming subgraph rather than by the
 * generator. {@link AbstractGraphitronApplication} removes the boilerplate so a concrete adapter
 * passes the facade's schema builder and SDL printer and, optionally, overrides
 * {@link #engineBuilder()} and {@link #rejectedIdentity(Throwable)}. A resource builds each request's
 * input itself and hands it to {@link GraphqlEndpoint}; {@link #newExecutionInput()} is only the
 * built-in {@code /graphql} endpoint's seam.
 *
 * @see AbstractGraphitronApplication
 */
public interface GraphitronApplication {

    /**
     * The single executable schema. Source of truth for both the engine and the {@code /schema}
     * SDL endpoint (printed through {@link #schemaSdl()}), so the two can never drift. Expected to be cached by the implementation
     * ({@link AbstractGraphitronApplication} builds it once); the library reads it freely.
     *
     * @return the built {@link GraphQLSchema}
     */
    GraphQLSchema schema();

    /**
     * The input for a request to the built-in {@code /graphql} endpoint, where no resource of the
     * subgraph's own has the request in hand: a per-request {@link ExecutionInput.Builder} the
     * subgraph populates with its {@code DSLContext} or session identity and any declared
     * {@code contextArguments} (via {@code Graphitron.newOwnedExecutionInput(...)} or
     * {@code Graphitron.newExecutionInput(...)}). The library layers {@code query} /
     * {@code variables} / {@code operationName} / {@code extensions} from the HTTP body on top, then
     * executes.
     *
     * <p>A subgraph that serves GraphQL from a resource of its own passes the input to
     * {@link GraphqlEndpoint#request(java.util.function.Supplier)} there, where the request's
     * values are at hand, and does not override this method: it returns {@code false} from
     * {@link #builtInEndpointEnabled()} instead. The default throws an
     * {@link IllegalStateException} naming those two ways out, so a built-in endpoint nobody
     * configured answers the redacted {@code 500} and logs the reason rather than running
     * unauthenticated.
     *
     * <p>Throwing a {@link jakarta.ws.rs.WebApplicationException} from this method is the supported
     * way to answer a client fault: {@link jakarta.ws.rs.NotAuthorizedException} for {@code 401},
     * {@link jakarta.ws.rs.ForbiddenException} for {@code 403}, and so on. The library re-throws it
     * unredacted so the container maps the status the adapter chose. Every <em>other</em> exception
     * escaping this method is treated as an internal fault and redacted to a reference-only
     * {@code 500}, with the real cause logged under a correlation id, so no adapter internals reach
     * the client. {@link #newReference(Throwable)} and {@link #redactedMessage(Throwable, String)}
     * choose that id and the text the client reads.
     *
     * @return a builder pre-wired with this request's per-request context
     */
    default ExecutionInput.Builder newExecutionInput() {
        throw new IllegalStateException(getClass().getName() + " does not supply an execution input for the"
            + " built-in /graphql endpoint: override newExecutionInput(), or return false from"
            + " builtInEndpointEnabled() and serve GraphQL from a resource that hands its input to"
            + " GraphqlEndpoint.");
    }

    /**
     * The reference the client is given for a fault the library redacts itself: one thrown while
     * building the execution input (the database down when {@link #newExecutionInput()} opens a
     * connection) never reaches a fetcher, so the generated {@code ErrorRouter} cannot answer it.
     * The library logs the fault under this reference. The default is a random UUID.
     *
     * <p>Override it, together with {@link #redactedMessage(Throwable, String)}, to give such a
     * fault the same reference and text as the generated {@code FetcherErrorResponse} gives a
     * fetcher fault, for example the OpenTelemetry trace id. This module cannot see the generated
     * interface, so have both overrides call the same helper of yours. If this method throws or
     * returns {@code null}, the default reference is used and one ERROR line names your class and
     * the class of what it threw.
     *
     * @param thrown the fault being redacted
     * @return the reference, logged with the fault and handed to {@link #redactedMessage}
     */
    default String newReference(Throwable thrown) {
        return RedactionDefaults.reference();
    }

    /**
     * The message of the one error the client receives for a fault the library redacts itself (see
     * {@link #newReference(Throwable)}). The default is
     * {@code "An error occurred. Reference: <reference>."}, the text the generated
     * {@code FetcherErrorResponse} defaults to. It is the client-facing text of a fault nobody
     * planned for, so do not put the exception's message in it unless you know what it contains.
     * If this method throws or returns {@code null}, the default text is used.
     *
     * @param thrown    the fault being redacted
     * @param reference the reference {@link #newReference(Throwable)} minted for it
     * @return the client's message
     */
    default String redactedMessage(Throwable thrown, String reference) {
        return RedactionDefaults.message(reference);
    }

    /**
     * The exception the session mount itself threw, when {@code thrown} is, or is caused by, a
     * rejected mount; empty otherwise. On the owned-connection engine a mount the database rejects
     * makes {@code GraphQL.execute} throw the generated {@code SessionMountException} (in your
     * {@code <outputPackage>.schema}), which this module cannot name; the generated facade's
     * {@code sessionMountFailure} recognises it, and {@link AbstractGraphitronApplication} takes that
     * method reference at construction. The default is empty, so an application that does not mount
     * identity never sees {@link #rejectedIdentity(Throwable)} called.
     *
     * @param thrown the exception that escaped
     * @return the mount's own exception, or empty when {@code thrown} is not a rejected mount
     */
    default Optional<Throwable> mountCause(Throwable thrown) {
        return Optional.empty();
    }

    /**
     * Answers a rejected session identity as a client fault, from the mount's own exception:
     * a rejected-identity {@code SQLState} to {@link jakarta.ws.rs.NotAuthorizedException}, say. Called
     * only when {@link #mountCause(Throwable)} found one, and before
     * {@link #clientFault(Throwable)}, so an application that overrides both gets this one for a
     * mount failure and {@code clientFault} for everything else.
     *
     * <p>A present value is thrown, unredacted, the way a {@link WebApplicationException} from the
     * input is, and the library logs nothing about the fault. Empty, the default, falls through to
     * {@link #clientFault(Throwable)} and then to the redacted {@code 500}. If this method throws or
     * returns {@code null}, the fault is redacted and one ERROR line names your class and the class
     * of what it threw.
     *
     * @param mountCause what the mount threw
     * @return the client fault to answer with, or empty to leave the decision to
     *         {@link #clientFault(Throwable)}
     */
    default Optional<WebApplicationException> rejectedIdentity(Throwable mountCause) {
        return Optional.empty();
    }

    /**
     * Answers any other exception that escaped the input or execution as a client fault instead of
     * redacting it. A rejected session identity is better answered by
     * {@link #rejectedIdentity(Throwable)}, which receives the mount's own exception; this is for
     * everything else a subgraph wants mapped to a status.
     *
     * <p>A present value is thrown, unredacted, the way a {@link WebApplicationException} from the
     * input is, and the library logs nothing about the fault. Empty, the default, redacts it to the
     * reference-only {@code 500}. If this method throws or returns {@code null}, the fault is
     * redacted and one ERROR line names your class and the class of what it threw.
     *
     * @param thrown the exception that escaped
     * @return the client fault to answer with, or empty to redact
     */
    default Optional<WebApplicationException> clientFault(Throwable thrown) {
        return Optional.empty();
    }

    /**
     * Engine assembly via graphql-java's own builder seam. The library caches
     * {@code engineBuilder().build()} once at application scope. The default delegates to
     * {@link #schema()} so there is exactly one built schema feeding both the engine and the SDL
     * endpoint; override to add an instrumentation (e.g. OpenTelemetry {@code GraphQLTelemetry}), a
     * custom {@code ExecutionStrategy}, or any future engine knob.
     *
     * <p>On the owned-connection engine build the builder from the generated runtime and pass any
     * further instrumentation to it: {@code runtime.newGraphQL(schema(), telemetry.newInstrumentation())}.
     * That keeps the connection instrumentation first in the chain. A later
     * {@code .instrumentation(other)} on the returned builder would replace it, after which no
     * identity is mounted and no connection is pinned.
     *
     * @return a {@link GraphQL.Builder} over {@link #schema()}, ready for {@code .build()}
     */
    default GraphQL.Builder engineBuilder() {
        return GraphQL.newGraphQL(schema());
    }

    /**
     * The SDL the {@code /schema} endpoint serves, printed from {@link #schema()} so it describes
     * the schema the engine executes. An adapter returns the generated facade's print of it,
     * {@code Graphitron.schemaSdl(schema())}, which on a federated subgraph is what its
     * {@code _service { sdl }} query answers and what Federation 2 composition from a schema URL
     * expects. {@link AbstractGraphitronApplication} does this when given the printer.
     *
     * <p>The default prints {@link #schema()} with graphql-java's default {@link SchemaPrinter},
     * which keeps an implementation written before this method compiling. That print is
     * <strong>not composition-ready for a federated subgraph</strong>: it omits the
     * {@code schema @link(...)} declaration and prints the GraphQL built-in directive definitions,
     * and a composer rejects both. The library cannot do better on its own, because the federation
     * print policy belongs to the generated code, which may name federation-jvm.
     *
     * @return the schema as SDL
     */
    default String schemaSdl() {
        return new SchemaPrinter().print(schema());
    }

    /**
     * Whether the library serves the bundled GraphiQL page at {@code GET /graphql} with
     * {@code Accept: text/html}. Defaults to {@code true}. Override to gate GraphiQL behind the
     * subgraph's own configuration, e.g. {@code return ConfigProvider.getConfig().getValue(...)}.
     *
     * <p>The toggle rides this SPI seam (a default method, like {@link #engineBuilder()}) rather than
     * a config framework on purpose: the framework decision is vendor-neutral Jakarta with no
     * RESTEasy/Quarkus types, and the project forbids adding dependencies not already pinned in the
     * parent pom, so the library cannot reach for Quarkus {@code @ConfigProperty} or MicroProfile
     * Config itself. A consumer that wants the toggle wired to its own config overrides this method;
     * the library stays dependency-free.
     *
     * @return {@code true} to serve GraphiQL, {@code false} to return {@code 404} for the HTML page
     */
    default boolean graphiqlEnabled() {
        return true;
    }

    /**
     * Whether the library's own {@link GraphqlResource} answers on {@code /graphql}. Defaults to
     * {@code true}. Override to {@code false} when the subgraph mounts
     * {@link GraphqlHttpHandler} on a path of its own and the ungated built-in route would be a hole
     * in that boundary: bean discovery registers the built-in resource automatically, so a consumer
     * cannot simply decline to use it.
     *
     * <p><strong>This is a 404 gate, not a de-registration.</strong> With the toggle off the
     * resource class is still discovered, still on the routing table, and still occupies the
     * {@code /graphql} namespace; every one of its routes answers {@code 404}. A consumer that needs
     * the route genuinely gone declares a {@link jakarta.ws.rs.core.Application} subclass with an
     * explicit {@code getClasses()}, which needs no support from this library. The trade-off is why
     * this toggle exists at all: in Quarkus such a declaration becomes definitive for the whole
     * application, which is a large hammer for excluding one resource.
     *
     * <p>Note also that mounting under {@code /graphql/{something}} shadows the built-in resource's
     * sub-paths whatever this toggle says, because Jakarta REST prefers the class with more literal
     * characters; see {@link GraphqlHttpHandler} for what that costs. The toggle governs the routes
     * that still reach the built-in resource, of which bare {@code /graphql} is the one that
     * matters.
     *
     * <p>The toggle rides this SPI seam for the same reason {@link #graphiqlEnabled()} does: the
     * library pulls no config framework, so a consumer that wants this wired to its own
     * configuration overrides the method.
     *
     * @return {@code true} to serve the built-in {@code /graphql} endpoint, {@code false} to answer
     *         {@code 404} on all of its routes
     */
    default boolean builtInEndpointEnabled() {
        return true;
    }
}
