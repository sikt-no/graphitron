---
id: R587
title: "Roadmap markdown code spans render as substituting AsciiDoc spans, so quoted macros go live"
status: In Progress
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-07
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

Note on this body's own notation: the AsciiDoc forms below were quoted in fenced blocks rather than inline
code spans, because until this item shipped a backticked example of the forms it discusses would itself
render live. That constraint is now lifted, and the sections written during implementation quote inline;
the emission rule keeps its fence because a two-line rule reads better as a block.

## Where the class lives today

Census over the markdown corpus (roadmap item bodies plus `changelog.md`), grepping backtick spans for
substitution triggers:

- Dozens of spans carry live syntax: brace-delimited attribute references (five spans quoting the Maven
  argLine property, all on one changelog line, plus placeholder vocabulary like the R560 body's
  message/table/select tokens and the R372 body's UPSERT/INSERT markers), Maven property syntax with a
  dollar prefix, and bare xref mentions. Each renders with the attribute-reference or macro substitution
  applied, or WARNs, instead of showing as typed.
- Three double-backtick spans, all carrying backticks in their content: the changelog's R227 entry quotes
  a generic map type with a pipe in its type arguments, `infer-node-from-implements-node-and-metadata.md`
  quotes a curated variant-table row mixing directive spans and a pipe, and
  `leaf-coverage-mention-classification.md` quotes a single backticked symbol reference. These drive the
  fallback-form trigger below: backtick content cannot sit inside a single-backtick wrapper at all.
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
code-span and prose segments (single- and double-backtick delimiters), apply the existing prose transforms
(bold, markdown-link rewrite via `LinkTarget`, em-dash sweep) to prose segments only, and emit span
segments in an inert monospace form (below). The four chains call it, and so
does the fifth md-sourced surface: front-matter titles, which reach adoc via `escapeAdocCell(i.title())`
and are distinct from the markdown heading titles above (the label-emitter section below is about this
fifth surface). Each surface keeps only its own structural post-step (the table cell's whole-cell pipe
escape). The ordered-list-marker rewrite stays a body-line-level transform applied before the inline
emitter.

Stated consequences of the unification, decisions rather than by-products:

- Backlog description lines on the status board gain the markdown-link rewrite and em-dash sweep they
  were silently missing.
- Heading titles gain the em-dash sweep.
- Prose transforms stop reaching inside code spans: today a markdown-link shape or an em dash inside
  backticks is rewritten mid-span; after this, span content is verbatim.

Two mechanics settled during implementation, both of them conditions for the above being true rather than
refinements of it:

- **Spans are held back from the prose transforms, not split out of the text.** Each span is replaced by a
  placeholder for the duration of the prose pass and restored after, which is Asciidoctor's own
  passthrough-extraction trick. Splitting was the obvious reading of "prose segments only" and it is
  wrong: bold wrapping a span, and a markdown link whose label carries one, are both common in the corpus,
  and neither pattern matches once the construct is cut in half. With the span standing in as one token
  they match unchanged. Restoration is also where the label context is honoured: a placeholder that ends
  up inside a rewritten macro's attrlist releases through the label emitter, everything else through the
  full producer.
- **The converter carries the open code-span delimiter across lines within a paragraph.** The sources are
  hard-wrapped, so a span opening on one line and closing on the next is ordinary; roughly forty sites do
  it. A line-local segmenter cannot tell that line's opening backtick from its closing one, so on a line
  that closes one span and opens another it pairs the closer with the opener and wraps the prose between
  them, corrupting content that renders correctly today (Asciidoctor pairs backticks over the joined
  paragraph, so the cross-line span works there). Carrying the open delimiter length, and resetting it at
  every paragraph or block boundary, is what makes line-local segmentation correct at all. The cross-line
  span itself still stays bare, which is the pinned non-goal below; the carry is what keeps it from
  taking its neighbours down with it.

### Inert span emission, a total function of span content

The segmenter first normalizes span content per CommonMark: a double-backtick span whose content starts
and ends with a space and contains a non-space character loses one pad space from each end (the pad is
delimiter syntax that lets content start with a backtick, not content). The emitter then picks the form
from the normalized content:

```
content without +, backtick, or leading/trailing whitespace  ->  `+content+`
content with any of those                                    ->  `pass:c[content]`   (any ] escaped as \])
```

The fallback trigger is wider than "contains a plus sign" because the corpus's three double-backtick
spans all carry backticks in their content: a backtick inside a single-backtick wrapper breaks the span,
and the plus-delimited form additionally needs non-space-adjacent delimiters. The pass macro survives
both because Asciidoctor extracts inline passthroughs before the quotes substitution pairs the wrapping
backticks; that ordering claim is one of the render-level probes below.

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
get an explicit label emitter: a backtick span in a title emits inert when the plus-delimited form can
carry its content and that content is also free of `]`, and stays a bare backtick span otherwise, with
the enforcement check below failing loudly on the bare residue so the author is told to rephrase the
title rather than shipping a substituting span silently. The clean predicate is the producer's own, plus
the `]` exclusion, not a separate list: the label emitter emits the plus-delimited form, which is equally
unsafe for content carrying a backtick or edge whitespace, and a second hand-written predicate is exactly
the drift the shared `InertSpans` unit exists to prevent. Today's corpus has backticked titles (nodeId,
implements-Node phrasing, service short-names) but none tripping any of the four, so nothing changes at
landing.

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
span, outside structural blocks, that is not in an inert form. The scanner tracks structural blocks and
skips listing, literal, comment, and passthrough regions, because fenced-block lines copy verbatim into
listing blocks where backticks are inert by block context, not by span form (the operation-driven test
corpus item has four bare backtick spans inside a graphql fence today, and a scan without block tracking
fails at landing on them). `AdocMarkdownTableCheck` is the in-repo model for the tracking, but its skip
set is not this one: it also skips `|===` table blocks, and a check that copied that would go blind to
the table-cell surface, including the status board's own tool-minted item-id spans this section cites as
what the gate catches. Four regions, not five. R582's collector plans the same exclusion. With blocks
excluded, the check needs no
macro-shape heuristic: the emitters route all flowed-prose monospace output through `InertSpans`,
including tool-minted spans such as the status board's item-id spans, so any bare backtick span in flowed
output means some surface bypassed the choke point, which is precisely the future regression (a later
md-sourced surface forgetting to route through the emitter) this gate exists to catch.

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
- Span content containing a plus sign, a backtick, or leading/trailing whitespace after pad
  normalization emits the pass-macro form with `]` escaped; content clean of all three emits the
  plus-delimited form. The three corpus double-backtick spans are the fixture cases, pinning both the
  CommonMark pad strip and the fallback routing.
- A span opened on one line and closed on the next stays a bare backtick pair (the pinned residual
  limit, asserted rather than only disclaimed; it is the one remnant of R582's observed cross-span
  fusing case, which per-span passthrough otherwise kills for md-sourced pages).
- Table-cell spans compose with the whole-cell pipe escape; heading spans convert; fence lines are
  untouched.
- Title label emitter: inert form when content is clean, bare span plus enforcement failure when content
  carries `]` or `+`.
- The corpus enforcement test described above. Harness: `TransientCitationCheckTest`'s pattern of
  walking up from the module basedir to the reactor root to read the real corpus files
  (`ModuleEnumerationCheckTest` and `CoverageAgentWiringCheckTest` do the same).

Render-level claims are verified empirically by planting probes and rendering, R582's method; roadmap-tool
does not grow an AsciidoctorJ test dependency for this. All three hold, read off
`docs/target/generated-docs/roadmap/`:

- **The plus-delimited form inside an xref attrlist.** Item titles carrying spans render as
  `<a href="plans/..."><code>...</code></a>` on the status board, monospace label intact and the link
  live; confirmed on the real backticked titles rather than on a planted one.
- **A pipe escaped inside a passthrough within a table cell.** `` `+Map<K\|V>+` `` in a cell renders
  `<code>Map&lt;K|V&gt;</code>`: the table parser consumes the `\|` before inline substitutions run, so the
  passthrough content shows the literal pipe. Same for the pass-macro form.
- **The pass macro's extraction preceding the quotes substitution**, which the backtick-content fallback
  depends on. The corpus's own three double-backtick spans render with their content backticks visible
  inside a monospace span (`<code>`Field`</code>`, `<code>`Map&lt;K|V&gt;`</code>`), so the wrapping
  backticks did pair around the already-extracted placeholder. The changelog's quoted block delimiter
  renders `<code>++++</code>` rather than the empty code span it publishes today, closing that census
  entry with no source edit.

End-to-end: full reactor build green under `-Plocal-db`; the docs render under its WARN gate stays green
as a secondary signal.

## Retired vocabulary

- `Main.escapeAdocInline`, the no-op inline-escape stub the status-board backlog description called. The
  unified pipeline replaces it; there is no "light escape for non-cell contexts" concept any more.
- The hand-written plus-delimited passthrough as an authoring convention in roadmap markdown. Authors
  write plain backticks; the emitter picks the form. A `` `+...+` `` in a `.md` body now means a literal
  plus on each side of the content, not a passthrough.

## Review feedback, In Review to Ready rework (`be60eba`)

Independent-session Done-gate review of `be60eba`. The mechanism ships correct and the acceptance holds:
full reactor green under `-Plocal-db`, `GeneratedAdocSpanGateTest` (11) and `InlineCodeSpanToAdocTest` (18)
both run in the base build, and the three render probes above are recorded with outcomes. Verified
empirically against the staged corpus rather than taken on the commit message's word: no U+0001 or U+0002
hold-placeholder leaks anywhere in `docs/target/staging/roadmap/`; zero surviving markdown-link shapes, so
the carried-open-delimiter state did not cost any link rewrite; em dashes 700 in source to 9 staged, and
every survivor sits inside a span or in the hand-authored `inference-axis-coverage.adoc` copy; bold 1665
to 4 staged, all four inside spans (`` `+**FieldClassification.Column**+` ``), which is the stated
"prose transforms stop reaching inside spans" consequence showing up as designed. Do not redo any of that.

One blocking gap, and it is the retirement sweep rather than the code.

**The R582 body reconciliation is incomplete, and the file now contradicts itself.** The spec put this
in scope ("Reconcile R582's body where it reasons from the premise ...") and named its audience (R582 is
in Spec, so its next Spec-to-Ready reviewer reads that body as current truth). The Design and Acceptance
sections were reconciled well; three sites were not, in `roadmap/adoc-xref-section-anchor-gate.md`:

- **Lines 197 to 202, the blocking one.** Every clause states the pre-R587 world in the present tense:
  "the md-to-adoc render currently passes backticks straight through", "every roadmap author has to know
  AsciiDoc passthrough syntax to quote a macro safely", "Emitting a passthrough span from the renderer
  would retire the whole class. This item does not wait on it; the one line here is rewritten by hand, at
  the cost of literal plus signs in GitHub's markdown view of the item." The hand-rewrite it describes was
  reverted in this same commit, and the sentence states the retired authoring convention as live practice,
  which is exactly what `roadmap/workflow.adoc`'s retirement sweep asks the Done-gate reviewer to catch
  ("prose paraphrasing the retired mechanism, which a token grep cannot catch"). A reviewer reading this
  paragraph reaches the opposite conclusion from the reconciled Design note three screens earlier.
- **Line 187** and **line 244**, both phrased "once this item's own quoted examples become / are
  passthroughs". The substance still holds (the count is zero on today's tree) but the mechanism named is
  the retired one; the examples are plain backticks now and the emitter is what makes them inert.

Not stale, deliberately: line 238's `docs/README.adoc` bullet keeps its plus-delimited passthroughs.
That is hand-authored `.adoc` under `docs/`, this item's pinned non-goal, so the paragraph-scope rule
still governs there. Leave it alone.

Why this holds the gate rather than becoming a follow-up: the `Retired vocabulary` section above is the
only grep handle a fresh reviewer has for the sweep, and it is deleted with this file at Done. Fixing the
survivor after approval means fixing it with the declaration already gone, and R582's own Spec-to-Ready
review may land first against a self-contradicting body.

### Non-blocking, pick up in the same pass or leave

None of these breaks the contract; they are in code this item already owns, so they are cheaper here than
as separate Backlog stubs. The reviewer's call either way.

- `Main.renderAdocByTheme` routes a tool-minted *attribute-entry* value through `InertSpans.monospace`,
  so `docs/target/staging/roadmap/by-theme.adoc:2` now ends its `:description:` value with a
  plus-delimited span around `theme:` where it used to carry a plain backtick one, and the published
  `<meta name="description">` carries literal plus signs. An attribute entry value takes the header
  substitution group, which has no macros, so no span was ever live there. The choke point is for flowed
  prose; either drop the span from that string or keep attribute values out of the emitter.
- The em-dash sweep in `inlineMdToAdoc` runs *after* `transformAdocLinks`, which releases a label's held
  spans early through `InertSpans.label`. A span inside a markdown link label therefore loses the verbatim
  guarantee for em dashes that a span in plain prose has. No corpus instance today; moving
  `prose.replace("—", ";")` above the `transformAdocLinks` call closes it.
- `titleLabel` applies `escapeAdocCell` on three non-cell surfaces (`appendBacklogAdocLine`, the deferred
  backlog list, the by-theme list). Pre-existing, not introduced here, but it now escapes a pipe *inside*
  an emitted passthrough on a list line, where no table parser consumes the backslash. No item title
  carries a pipe today, so nothing renders wrong; the smell is that the cell escape outlived the cell.
- The spec's title-label verification bullet promises "bare span **plus enforcement failure**".
  `titleLabel_goesInertOnlyWhenTheAttrlistCanCarryIt` pins the bare-span half; nothing composes that bare
  residue with `InertSpans.scan` to pin that the gate actually fails on it. One assertion.
- `roadmap/adoc-xref-section-anchor-gate.md:166` picked up a 148-character line from the reconciliation
  edit; rewrap to the file's width.

### Rework applied

All six taken, the blocking one and all five notes.

- **R582 reconciliation completed.** The "Two things to keep straight about scanning roadmap prose"
  paragraph now states the pre-change world in the past tense and names R587 as shipped, and the sentence
  describing the hand-written workaround is gone rather than reworded, because the workaround itself is.
  Both "once this item's own quoted examples become / are passthroughs" phrasings now say the renderer
  emits them inert. The `docs/README.adoc` bullet keeps its plus-delimited passthroughs untouched, as the
  review directed: hand-authored `.adoc` under `docs/` is this item's pinned non-goal and the
  paragraph-scope rule still governs there. A grep for `passthrough` across the file leaves six hits, all
  of them either history in the past tense or about that non-goal habitat.
- **The by-theme `:description:` no longer carries a span at all.** An attribute entry takes the header
  substitution group, which has no macros, so nothing was live there, and a plain-text meta description is
  the right shape for the sink. The scanner is unchanged: an attribute value is in its scan, and a future
  span in one gets flagged, which pushes toward the same answer.
- **The em-dash sweep moved above the link rewrite**, so a span inside a markdown link label keeps the same
  verbatim guarantee as one in plain prose rather than losing it to the early label release.
- **`titleLabel` dropped the pipe escape.** It emits onto list lines only; `titleCell` keeps the escape and
  is the one cell surface. The cell escape no longer outlives the cell.
- **The title-label verification bullet is fully pinned.**
  `titleLabel_bareResidue_failsTheCorpusGate` composes the bare residue with `InertSpans.scan` and asserts
  the gate reports it, which is the "plus enforcement failure" half the bullet promised.
- **Line 166 rewrapped.** The other over-width lines in that file predate this item and are left alone.

## Non-goals

- Code spans crossing line boundaries. The converter is line-based and stays so; the limit is pinned by
  test as above.
- Fence handling, the `LinkTarget` classification model, and the markdown `README.md` generation
  (backticks stay markdown there).
- HTML concept explainer pages (different pipeline, no adoc emission).
- Hand-authored `.adoc` under `docs/`: authors there write AsciiDoc on purpose, and the dangling-anchor
  class in that habitat is R582's.
