---
id: R911
title: "The rewrite package name outlived the rewrite"
status: Backlog
bucket: cleanup
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-02
last-updated: 2026-09-02
---

# The rewrite package name outlived the rewrite

## Goal

`no.sikt.graphitron.rewrite` is named for a project rather than a job. It was the package the
next-generation generator was built in while the old one still stood, and now that nothing else
stands it names nothing: a contributor reading it learns which era the code was written in, not what
the code does. It also carries the whole generator, 385 files, next to job-named siblings
(`command`, `render`, `plan`, `facts`) that grew up around it and say what they are. When this lands
the generator's packages are named for what they do, and a reader looking for the emitters or the
planner finds them by name instead of by grep.

R865 takes the first bite by construction: the 89 files that move into the fact tier land under
`no.sikt.graphitron.model.*`, because leaving them in `rewrite` would declare eight packages in two
modules at once and cost the boundary that item exists to create. This item is the other 296, plus
the type and parameter names that carry the same word (`GraphQLRewriteGenerator`,
`AbstractRewriteMojo`, `RewriteResult`, the `preRewriteSchema` parameters). It was deliberately not
ridden along with the module move: a reactor-wide rename in the same commit as a module boundary
change makes both unreviewable, and this half has no module boundary in it at all.

The work is finding the homes, not doing the renames. `command`, `render`, `plan` and `facts` are
evidence that job-named packages fit this code, and they are also evidence that nobody has yet had to
decide where the remaining 296 belong. That decision is the item.
