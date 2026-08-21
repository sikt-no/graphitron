---
id: R786
title: "MCP issue.report tool: dedup search over issues, roadmap and changelog, plus a draft report"
status: Backlog
bucket: mcp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# MCP issue.report tool: dedup search over issues, roadmap and changelog, plus a draft report

A schema author who hits a graphitron bug or limitation has no tool-assisted path from "this looks wrong" to a well-formed report. Today they grep the GitHub issue tracker by hand (or don't), and the report they eventually write rarely carries what a maintainer needs first: the graphitron version, the schema slice around the failing coordinate, and what the classifier or diagnostics actually said. The result is duplicate issues, reports of bugs already fixed in a newer version, and triage round-trips asking for the schema.

The MCP server should grow an `issue.report` tool that does two things in one call, following the server's existing convention that tools answer and the calling agent orchestrates:

1. **Dedup candidates.** Search three corpora for prior art on the described problem and return ranked candidates, each carrying its source kind (open GitHub issue, active roadmap item, shipped changelog entry), a URL or title, and enough body excerpt that the calling session can judge "same bug", "same area but different symptom", or "unrelated" without another fetch. A shipped-changelog hit is its own verdict: the fix may already exist in a version newer than the consumer's.
2. **A draft report.** Always returned alongside the candidates, never conditionally: similarity scores cannot decide duplication, the calling agent and the human do. The draft is assembled from the live fact store (graphitron version, the SDL slice and directive applications around the given coordinate, the diagnostics entries or classification verdict) plus the author's free-text description. It is sectioned (environment / schema slice / observed behavior) so the same content serves two endings: the body of a new issue, or a comment on an existing near-match issue ("same symptom, my variant differs in X"). The tool never posts anything; a human reviews the draft (consumer schemas carry internal table and column names) and files or comments themselves.

## Settled design direction

- **Input:** a free-text problem description (required; it is the search query) plus an optional schema coordinate. With a coordinate, the draft embeds that coordinate's diagnostics and SDL slice; without one, it falls back to what diagnostics currently reports.
- **GitHub issues are searched live** via the public search API (the repo is public; unauthenticated access suffices for a per-invocation tool). GitHub's lexical search is adequate for short keyword-dense titles; no embedding needed for this corpus. This leg is also what keeps dedup fresh: anything reported or fixed after the consumer's release surfaces here.
- **Roadmap and changelog are fetched from trunk at dev-session startup and embedded at runtime**, reusing the `catalog.search` pattern (`CatalogSearchIndex`): an `AsyncWarm`-managed background embed, a content-hash-keyed Lucene index persisted across dev restarts so an unchanged corpus costs one HTTP GET plus a hash check, an embedder-identity manifest, and a warming degradation reported honestly while the index builds. Offline, the tool serves the last cached index and reports its age; with no cache it says so rather than fabricating.
- **One chunk per roadmap item and per changelog entry.** The unit of deduplication is the item, so retrieval granularity matches it; no tail chunks. The BGE embedder truncates at 512 tokens regardless, which is acceptable because roadmap items are written top-down and the opening carries the thesis. The index is hybrid: the vector covers the head, the Lucene text field indexes the full body, so a distinctive error message deep in an item still matches lexically. Corpus scale is roughly 700 chunks (active items plus changelog entries), well under what the catalog index already handles.
- **A separate bundle and index from `docs.search`.** The docs index deliberately scopes the roadmap and changelog out as contributor-internal authoring surfaces; this tool includes them for a different purpose (dedup), and keeping the indexes separate preserves that earlier decision rather than silently reversing it. Candidates link by URL and title, never by bare `R<n>` id, since item ids are transient.
- **Follow-up for the workflow doc:** one sentence in `roadmap/workflow.adoc` making the retrieval contract explicit: the opening of an item is its retrieval surface, so an item that buries its point past the first ~500 tokens is measurably worse at dedup, not just stylistically worse.

## Open questions for Spec

- Corpus fetch mechanics: raw.githubusercontent.com from trunk versus the GitHub contents API; how the trunk branch name is configured; behavior when the fetch races a trunk push.
- Whether the changelog's very long entries want a token-budgeted head extraction before embedding, or whether tokenizer truncation alone is fine.
- Whether GitHub issue search results should be re-ranked by the local embedder against the query, or GitHub's own ranking passed through.
- Exact tool name (`issue.report` versus `issues.search` + `issue.draft` split) if the one-call shape proves awkward in the MCP client.
