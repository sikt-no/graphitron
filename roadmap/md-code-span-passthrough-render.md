---
id: R587
title: "Roadmap markdown code spans render as substituting AsciiDoc spans, so quoted macros go live"
status: Spec
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-06
---

# Roadmap markdown code spans render as substituting AsciiDoc spans, so quoted macros go live

A markdown inline code span is literal by definition; an AsciiDoc one is not, because a single-backtick
span applies the normal substitution group with macros included. The roadmap md-to-adoc render passes
backticks straight through (`LeafCoverageReport` notes "AsciiDoc handles backticks for code spans"), so a
roadmap author who quotes a macro in backticks publishes a live one. A backticked xref yields a real link
on the rendered roadmap page, and a backticked attribute reference resolves, or warns and fails the docs
build under its `failIf severity=WARN` setting, rather than showing as typed. Writing this item's own
problem statement hit the second case. The author cannot see this from the markdown source, and the
workaround is to know AsciiDoc passthrough syntax while writing markdown, which defeats the point of
authoring items in markdown. Emitting a passthrough span instead of a bare backtick span would retire the
class for every item at once. Surfaced while reviewing the dangling-cross-file-xref-anchor gate, whose own
body is the first instance: that item hand-passthroughs its three quoted xrefs rather than waiting on this.

Note on this body's own notation: every AsciiDoc form quoted below sits in a fenced block, not an inline
code span, because until this item ships a backticked example of the forms it discusses would itself
render live. Fences are literal in both grammars.

## Where the class lives today

Census over the markdown corpus (roadmap item bodies plus `changelog.md`), grepping backtick spans for
substitution triggers:

- Dozens of spans carry live syntax: brace-delimited attribute references (six spans quoting the Maven
  argLine property alone, plus placeholder vocabulary like the R560 body's message/table/select tokens and
  the R372 body's UPSERT/INSERT markers), Maven property syntax with a dollar prefix, and bare xref
  mentions. Each renders with the attribute-reference or macro substitution applied, or WARNs, instead of
  showing as typed.
- The R223 changelog entry quotes the AsciiDoc passthrough block delimiter (four plus signs) in backticks.
  Asciidoctor parses the emitted span as an empty unconstrained passthrough pair inside a monospace span,
  so the published changelog renders an empty code span where the delimiter should show.
- `adoc-xref-section-anchor-gate.md` (R582, in Spec) hand-writes 17 plus-delimited AsciiDoc passthroughs
  inside markdown backticks; the workaround this item exists to retire. (The problem statement above says
  three; the body grew after filing.)

There is a second, structural half to the finding. The renderer has four inline-transform chains, each
hand-composed with a different subset in a different order: heading titles get link rewrite then bold
(`Main.java` heading branch), body lines get list markers, bold, links, then the em-dash sweep, table
cells get bold, links, em-dash, then the pipe escape (`transformAdocTableCell`), and the status-board
backlog description gets nothing at all (`escapeAdocInline` is a no-op stub, so those lines are missing
the link rewrite and em-dash sweep entirely). Nothing binds the four; the differences read as accidents.
Threading span handling through each of them as a fifth hand-composed ingredient would repeat the smell,
so the design below collapses them first.

## Design

### One inline emitter as the choke point

`Main` gains a single inline pipeline, `inlineMdToAdoc(String, ChangelogContext)`: split the text into
code-span and prose segments (single- and double-backtick delimiters, spans within one line only), apply
the existing prose transforms (bold, markdown-link rewrite via `LinkTarget`, em-dash sweep) to prose
segments only, and emit span segments in an inert monospace form (below). All four surfaces call it;
each keeps only its own structural post-step (the table cell's whole-cell pipe escape). The
ordered-list-marker rewrite stays a body-line-level transform applied before the inline emitter.

Stated consequences of the unification, decisions rather than by-products:

- Backlog description lines on the status board gain the markdown-link rewrite and em-dash sweep they
  were silently missing.
- Heading titles gain the em-dash sweep.
- Prose transforms stop reaching inside code spans: today a markdown-link shape or an em dash inside
  backticks is rewritten mid-span; after this, span content is verbatim.

### Inert span emission, a total function of span content

```
content without a plus sign   ->  `+content+`
content with a plus sign      ->  `pass:c[content]`   (any ] escaped as \])
```

Uniform, not conditional on content looking macro-like: markdown already decided the span is literal at
the parse boundary, and a macro-shape predicate would re-derive Asciidoctor's grammar apart from
Asciidoctor, free to drift at every AsciidoctorJ upgrade. R582 already won this argument against
reimplementing the section-id generation algorithm; same reasoning. Two emitted forms rather than
pass-macro-always because the staged adoc is a read artifact (R582's planned gate parses it, authors
debug renders from it), and the plus-delimited form keeps the common case readable; the pass macro is
the narrow exception, and the rule stays a pure function of content so the form set is enumerable in a
test.

The emitter lives as a small named unit, an `InertSpans` type (or equivalent) owning both the producer
(`monospace(String)`) and the matching recognizer, so "which span forms are inert" is single-sourced.
R582's collector will classify span forms on exactly the pages this tool generates and must read this one
definition rather than keep a second list that drifts.

### The xref-label context is different

`escapeAdocCell(i.title())` feeds two structurally different sinks: table cells, and the label attrlist
of xref macros on the status board and by-theme pages. Inside an attrlist a `]` terminates the macro
lexically, before inline substitutions run, so the pass-macro fallback is structurally unsafe there, and
the plus-delimited form must be verified against a rendered probe rather than assumed. Titles therefore
get an explicit label emitter: a backtick span in a title emits inert when its content is free of `]` and
`+`, and stays a bare backtick span otherwise, with the enforcement check below failing loudly on the
bare residue so the author is told to rephrase the title rather than shipping a substituting span
silently. Today's corpus has backticked titles (nodeId, implements-Node phrasing, service short-names)
but none carrying `]` or `+`, so nothing trips at landing.

### LeafCoverageReport shares the emitter, not the segmentation

`LeafCoverageReport.cleanJavadocInline` (the method whose comment the problem statement quotes) mints
monospace spans from javadoc code and link payloads into the staged coverage reports, the same
substituting-span class. It switches to the shared producer. The markdown segmentation stays out of it:
javadoc input has no author-written backtick grammar to parse, so importing the parser would add a
concern the input does not have.

## Enforcement

The docs-profile WARN gate is not the enforcer for this class. It is configured on the site render
execution inside the docs profile, so it does not run under the `-P!docs` build CLAUDE.md itself
recommends, and R582's empirical probes showed a live cross-file xref renders completely silently (no
INFO, no WARN, build success), so the WARN gate misses exactly the half of the class this item's title
names. The invariant "generated roadmap adoc carries no substituting monospace span" gets a structural
enforcer in the base build instead: a roadmap-tool test renders the real corpus (every item body, the
changelog, the status board, the by-theme page) through the adoc renderers and fails on any monospace
span not in an inert form. Because the emitters route all monospace output through `InertSpans`,
including tool-minted spans such as the status board's item-id spans, the check needs no macro-shape
heuristic: any bare backtick span in output means some surface bypassed the choke point, which is
precisely the future regression (a later sixth surface forgetting to route through the emitter) this
gate exists to catch.

## Migration and R582 coordination

In the same change:

- Rewrite the 17 hand-passthrough spans in `adoc-xref-section-anchor-gate.md` to plain markdown
  backticks. Required, not cosmetic: their content contains plus signs, so under the new emitter they
  route to the pass-macro form and would render their plus delimiters literally, changing the published
  page.
- Reconcile R582's body where it reasons from the premise "a backticked reference in roadmap prose
  renders live" (its Design note on cross-span attrlist fusing and its collector test list distinguish
  single-backtick spans from plus-delimited passthroughs). Post-R587 that premise still holds for
  hand-authored `.adoc` under `docs/` and is false for md-sourced pages, and its collector should
  classify span forms via the shared recognizer. R587 lands first; R582 is in Spec, so this body edit is
  a plan revision its next Spec-to-Ready reviewer sees as current truth.
- The changelog's quoted block delimiter needs no source edit; the new emitter renders it correctly via
  the pass-macro form.

## Verification

Converter-level unit tests in roadmap-tool, `MdTableToAdocTest` conventions:

- A backticked xref macro in body prose emits the plus-delimited form; a backticked attribute reference
  likewise; both therefore render as typed.
- A markdown link inside a span is not rewritten; the same link outside the span still is. An em dash
  inside a span is preserved; outside, swept.
- Span content containing a plus sign emits the pass-macro form with `]` escaped.
- A double-backtick span converts; a span opened on one line and closed on the next stays a bare backtick
  pair (the pinned residual limit, asserted rather than only disclaimed; it is the one remnant of R582's
  observed cross-span fusing case, which per-span passthrough otherwise kills for md-sourced pages).
- Table-cell spans compose with the whole-cell pipe escape; heading spans convert; fence lines are
  untouched.
- Title label emitter: inert form when content is clean, bare span plus enforcement failure when content
  carries `]` or `+`.
- The corpus enforcement test described above.

Render-level claims (the plus-delimited form inside an xref attrlist; a pipe escaped inside a passthrough
within a table cell) are verified empirically during implementation by planting probes and rendering,
R582's method, with the outcome recorded in this body; roadmap-tool does not grow an AsciidoctorJ test
dependency for this.

End-to-end: full reactor build green under `-Plocal-db`; the docs render under its WARN gate stays green
as a secondary signal.

## Non-goals

- Code spans crossing line boundaries. The converter is line-based and stays so; the limit is pinned by
  test as above.
- Fence handling, the `LinkTarget` classification model, and the markdown `README.md` generation
  (backticks stay markdown there).
- HTML concept explainer pages (different pipeline, no adoc emission).
- Hand-authored `.adoc` under `docs/`: authors there write AsciiDoc on purpose, and the dangling-anchor
  class in that habitat is R582's.
