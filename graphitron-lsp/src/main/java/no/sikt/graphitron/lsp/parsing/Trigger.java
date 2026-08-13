package no.sikt.graphitron.lsp.parsing;

/**
 * What a language-server request is about, as a closed vocabulary. The parse reads
 * the request and the buffer and produces one of these; each surface dispatches it
 * through an exhaustive switch and the arm it selects queries the fact store and
 * renders the result. There is no model between the switch and the store.
 *
 * <p>The hierarchy is sealed for two reasons that coincide. A switch that is not
 * exhaustive over a sealed subject needs a {@code default} arm, which is how a
 * capability goes quietly unanswered; and the dispatch matrix
 * ({@link no.sikt.graphitron.lsp.dispatch.TriggerDispatch}) draws its whole universe
 * from {@link Class#getPermittedSubclasses()}, so a new trigger cannot be added
 * without every surface declaring what it does with it. A hand-listed vocabulary
 * would put that obligation in a list somebody has to remember to edit.
 *
 * <p>Three families, split by what keys them rather than by which surface consumes
 * them (most triggers feed more than one surface):
 *
 * <ul>
 *   <li>{@link Behavior}, the value bindings: a cursor inside a directive-argument
 *       value, where the coordinate names which keyset the value is drawn from.
 *   <li>{@link CursorToken}, the other cursor positions: a token that is a name
 *       rather than a bound value.
 *   <li>{@link DocumentScan}, the whole-document sweeps: no cursor at all, keyed by
 *       the document (or by a report entry that names one).
 * </ul>
 *
 * @see no.sikt.graphitron.lsp.dispatch.TriggerDispatch
 */
public sealed interface Trigger permits Behavior, Trigger.CursorToken, Trigger.DocumentScan {

    /**
     * A cursor on a name token, as opposed to a value bound by a directive argument
     * (which is {@link Behavior}). What each surface makes of one of these varies more
     * than the value bindings do: a directive name is a hover subject and a diagnostic
     * subject but never a completion subject, because the incumbent never completed it.
     */
    sealed interface CursorToken extends Trigger {

        /**
         * The cursor is on a directive name, {@code @table} in {@code @table(name: "x")}.
         * Hover renders the directive's own description; diagnostics report a name that
         * resolves to no definition. Bundled and user-declared directives are one case,
         * because capture parses the bundled vocabulary like any other schema file.
         */
        record DirectiveName() implements CursorToken {}

        /**
         * The cursor is on a directive argument's name, or inside a directive's argument
         * list at no argument in particular. Completion offers the names the directive
         * declares; hover renders the argument's docstring; diagnostics report names the
         * directive does not declare and required names that are absent.
         */
        record DirectiveArgName() implements CursorToken {}

        /**
         * The cursor is on the name of an SDL declaration: the {@code Film} in
         * {@code type Film}. Hover renders the classification block behind a config
         * toggle, and definition jumps to whatever Java the declaration is bound to.
         */
        record SdlDeclarationName() implements CursorToken {}

        /**
         * The cursor is on a reference to a type from somewhere else in the SDL: a field's
         * type, an argument's type, an implemented interface. Definition resolves it to
         * every site that declares the type, which is more than one when the type is
         * assembled from several files.
         */
        record SdlTypeReference() implements CursorToken {}
    }

    /**
     * No cursor: the request is about a whole document. Inlay hints and code actions
     * arrive with a range, but the range filters the result rather than selecting the
     * arm, and diagnostics are pushed with no request at all. Each leaf is a separate
     * sweep with its own fact source, so they are separate triggers even where one
     * surface runs several of them for one request.
     */
    sealed interface DocumentScan extends Trigger {

        /** Inlay hints labelling each declaration with its classification verdict. */
        record ClassificationHints() implements DocumentScan {}

        /**
         * Inlay hints showing the directives a declaration would carry if the author had
         * written what the generator infers.
         */
        record InferredDirectiveHints() implements DocumentScan {}

        /**
         * Inlay hints for the absence arm of the inferred-directive pass: a directive the
         * generator does not infer, where the author might expect it to.
         */
        record AbsentDirectiveHints() implements DocumentScan {}

        /**
         * A registered SDL refactor's detector, re-scanning the document for the literals
         * it migrates. Each match offers a fix at three scopes: the site, the file, and
         * the workspace.
         */
        record SdlActionDetectors() implements DocumentScan {}

        /**
         * A lint finding for this document that carries a fix. The finding is the trigger
         * and the rule owns the edit, so this is keyed by the report rather than by
         * anything in the buffer.
         */
        record LintFindings() implements DocumentScan {}

        /** Directive arguments applied but not declared, over every application in the document. */
        record UnknownArgs() implements DocumentScan {}

        /** Directive arguments declared required but absent, over every application in the document. */
        record RequiredArgs() implements DocumentScan {}

        /** Directive applications whose name resolves to no definition, spec built-ins aside. */
        record UnknownDirective() implements DocumentScan {}

        /**
         * What the schema build itself said about this document: the parse errors of a file
         * that would not parse, and the validation errors of a schema that would not
         * assemble. Both are rows, so this reports facts rather than the absence of them.
         */
        record SchemaValidation() implements DocumentScan {}
    }
}
