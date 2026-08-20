---
id: R741
title: "Date or refresh the parallelism figure in graphitron's junit-platform.properties"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Date or refresh the parallelism figure in graphitron's junit-platform.properties

`graphitron/src/test/resources/junit-platform.properties` justifies enabling class-level test parallelism in this module and only this module, and cites "the 170.5s-to-117.1s measurement behind the change" as the reason the module is the first. That figure is real but was taken on a tree that no longer exists: it is the pre-batching experiment, run before the column-match sweep stopped reading its claim view once per graph. On the tree the file actually ships in, the module measures 98.4s sequential against 70.6s at four threads, so the comment overstates both the module's sequential baseline and the win parallelism buys, by roughly a factor of two on the delta.

Nothing behaves wrongly, and the sentence is defensible as written, since it names the measurement *behind* the decision rather than a measurement of the result. The cost is that the same comment invites extending these properties to the other classifying modules one at a time, and whoever takes that up will size the expected win off a baseline this module no longer has. Two fixes are available and the choice is the point of the item: date the figure in place so a reader knows which tree it describes, or replace it with the post-batching pair and note that the earlier experiment is what motivated the change. Either way the general question is worth a sentence: a wall-clock number written into a permanent file has no gate keeping it true, so the convention should be to date such figures rather than to state them bare.

One clause in that comment has since become false rather than merely stale, and it is a different
defect from the figure. The file says it enables parallelism "in this module and only this module".
Four modules now run their test classes concurrently: `graphitron-model` gained its own properties
file, and that file rides along in `graphitron-model`'s test-jar onto the test classpaths of
`graphitron`, `graphitron-lsp` and `graphitron-mcp`. Whoever fixes the figure should fix the scope
claim in the same pass, and R764 carries the mechanism.
