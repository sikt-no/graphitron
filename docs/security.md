# Security

## Why database-level security

Security logic can live in many places: the database, a service layer, API middleware, the UI. For our context, the database makes sense:

The system spans decades. The database outlasts any individual application — rules in the database persist, rules in applications must be intentionally carried forward. Multiple applications access the same data, so database-level rules apply uniformly without coordination. And rules expressed as data predicates are discussable with the people who understand the business. Ask a registrar who should see student grades and they can tell you. A database has a finite number of tables and columns — you can point at one and have a concrete conversation.

Other systems — shorter-lived, single-application, or with authorization logic that doesn't map to data predicates — might reasonably choose differently. This isn't a universal prescription.

## What this means for Graphitron

Graphitron's generated code is intentionally naive about security. It doesn't include authorization checks. The database handles enforcement; the generated code stays simple. If Graphitron tried to generate authorization logic, it would be reimplementing what the database already does, and adding something that could drift out of sync.

## The UI still matters

Database enforcement doesn't mean the UI can ignore security. Good usability requires that users don't see buttons they can't click or forms they can't submit. The rules should be the same in both places; the implementations differ.

What we insist on: **the database is the enforcement point.** If the UI gets it wrong, the result is a confusing experience. If the database gets it wrong, data is exposed. These are not equivalent failures.

The practical approach is PostgreSQL Row-Level Security with session variables set per request. See [Runtime Extension Points](../graphitron-rewrite/docs/runtime-extension-points.md) for how that wires together with `GraphitronContext`.
