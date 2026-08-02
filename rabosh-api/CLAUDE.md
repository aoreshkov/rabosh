# rabosh-api — module conventions

Cross-cutting design rules live in the root `CLAUDE.md`; testing conventions in `.claude/rules/testing.md`.

`rabosh-api` holds `Rabosh` and `RaboshOptions` and nothing else. It is **ergonomics over the layers,
never a second definition of them**: no planner, no matcher, no format, no new concept. If something here
needs a concept the layers do not have, that concept belongs in the layer below — which is why the surface
is deliberately narrow (writes, reads, scans, queries, index management, the model) and `db.store`,
`db.catalog` and `db.indexCatalog` stay public and unwrapped rather than being re-exported. A facade that
restated five modules would drift from them; the differential suite exists to pin that the queries it
answers are the engine's, unaltered, and it compares against a third oracle — arithmetic on the index that
generated each document — because `rabosh-query`'s two oracles are `internal` and re-implementing one here
would give this module the private definition of a predicate it must not have.

Three things there are decisions rather than defaults, and each fails in its own way.

**One backfill pass, not one per layer.** `attach` attaches each layer with `backfill = false`, runs a
single `DocumentStore.backfill` through one `CompositeSegmentObserver`, and then attaches each layer
again. That second attach is not redundant: both layers deliberately re-read `liveSegmentNumbers`
*after* their own scan, because a compaction landing during a long backfill makes the pre-scan set wrong
for reclamation. Shortcutting it would delete the sidecar of a segment that is alive. `SchemaCatalog`
gained `attach(store, backfill)` for this, mirroring the one `IndexCatalog` already had, and
`StoreOptions.withSegmentObserver` exists so that composing into that slot does not mean restating every
store option — an option added to `StoreOptions` would otherwise be silently dropped by a facade nobody
updated.

**`close` closes the store first, then the index catalog.** `DocumentStore.close` stops maintenance
before anything else, and stopping it *joins* the worker rather than cancelling it — so a flush already
inside its body completes and reports to an observer that is still open. The other order makes that
flush's `beginSegment` return `null` and its sidecars silently never exist: correct, since a missing
sidecar reads as uncovered, but a segment's worth of work thrown away. Neither close deletes anything —
an index catalog *releases* rather than retires, because shutting down is not departing — so the Windows
rule about mapped files does not decide the order; what it decides is that both must release before the
directory can be removed, which is why the acceptance test **deletes the directory** rather than
measuring anything. The ordering window itself cannot be raced deterministically (`Maintenance.close`
also abandons merely *pending* work), so what the suite pins is the invariant and the argument lives in
the KDoc.

**The planner's statistics are cached against the live segment set; the model is not cached at all.**
`QueryEngine` takes the index catalog as a live object and the schema as a value, so a newly created
index is used by the very next query with no refresh anywhere, and the only thing that can go stale is
the fold — which can only change when the set of live segments changes. `db.schema()` folds fresh every
call, because a caller asking for the model is asking about the data now and serving that from a fold
taken at some earlier flush would be a different answer wearing the same name.
