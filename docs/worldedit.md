# WorldEdit / FAWE block history

Prism stores WE changes in its own compressed files and stores only operations and
16×16×16 section indexes in SQL. WE changes do not create individual `records` or
`extra` rows. A dense, section-aligned 128×128×64 edit uses one operation row and
256 section rows for 1,048,576 changed blocks. Sparse edits achieve a smaller row
reduction. Files still contain the full before/after states and tile NBT.

## Runtime and configuration

- Minecraft 1.12.2 / SpongeForge with Sponge API 7.1.
- Plain WorldEdit Forge 6.1.10, or
  `FastAsyncWorldEdit-forge112-19.11.13-5505943-1282-22.3.5.jar` with its matching WE6.
- H2 or MySQL/MariaDB. MongoDB prints a startup warning and does not activate WE
  capture or WE query merging.
- The FAWE adapter checks the actual commit and the Forge queue API. Other FAWE
  builds and offline/anvil queues are rejected.

Add this entry to FAWE's existing `extent.allowed-plugins` list and restart:

```yaml
extent:
  allowed-plugins:
    - com.helion3.prism
```

Retain any other entries already in that list. Prism does not rewrite FAWE's config.
Without this allowance FAWE would discard Prism's Extent; the adapter rejects the
edit and explains the missing configuration. The Extent keeps FAWE's asynchronous
placement and its history callbacks, but disables some of FAWE's bypass optimizations.
Measure editing time on your own server before production rollout.

For Forge112 build 5505943, capture uses the actual cached chunk from
`MappedFaweQueue`'s queue map; `ForgeQueue_All.getFaweChunk()` is a factory.
The adapter preserves the native queue map and wraps its dispatch boundary to verify
changes after all workers in that batch have joined. It does not rely on
`FaweChunk.addNotifyTask()`, which this Forge dispatch implementation never executes.

Prism generates this configuration category:

```hocon
worldedit {
    enabled=true
    buffer-mib=64
    pending-mib=1024
    minimum-free-mib=256
    blocks-per-tick=200
    milliseconds-per-tick=5
}
```

The capture budget accounts for queued changes, buffered frames, ownership maps and
before images. Temporary codec allocations and FAWE's own queues are additional
memory. A single change's before/after states and NBT must fit within 8 MiB. Frame
compression and file I/O run on a dedicated writer. The capture budget, disk health
and unpublished file budget reject further edits when recording cannot keep up.
A capture/codec/disk fault requires investigation and a restart; SQL publication
failures are retried automatically every five seconds while the pending budget permits.

## Commands and behavior

Existing lookup, inspection, rollback, restore and Prism undo include WE history:

```text
/pr lookup e:worldedit p:Builder t:1h r:20
/pr rollback e:worldedit p:Builder t:1h r:20
/pr restore e:worldedit p:Builder t:1h r:20
/pr undo
```

Existing permissions and parameter defaults apply. Omitting `e:worldedit` includes
both ordinary and WE history. Hover over an individual WE record to see its
operation UUID, source, status and before/after states. Deletion matches the old
block for `b:`, and placement/replacement matches the resulting block.

Masks and failed/no-op changes do not create successful changes. Queue entries
overwritten before dispatch do not become phantom history. Each confirmed write
to the world retains its capture order. New ordinary records also retain capture
time/order; old records retain their existing second-level precision.

`maximum-lookup` and `maximum-actionable` apply after the sources are merged.
Aggregated lookup counts include all matching changes. Action limits count actual
block changes and display a truncation notice. Large reversals and Prism undo yield
between ticks. Rollback keeps Prism's existing forced snapshot restore semantics.

Direct block changes through standard EditSession extents are supported, including
set, replace, paste, brushes and WE undo/redo. Entities, biomes and offline region
editing are excluded. `//regen` and anvil commands are rejected because they bypass
the supported capture path. Third-party code writing directly to FAWE queues or
native world/chunk data is outside the EditSession integration contract.

## Files, recovery and backups

Archives live in `worldedit/` beside Prism's configuration file. Never delete that
directory independently of the SQL indexes. Back up the database and archive
directory together, preferably with editing stopped and the writer drained.
Each archive has a versioned footer, section directory, palettes, typed binary NBT,
and independently compressed/checksummed frames. Queries seek only relevant sections.
Before a rollback starts, all candidate frames are validated; a missing or corrupt
file fails the query instead of silently applying an incomplete result.

Files are synced and atomically renamed before SQL publication. `.pending` manifests
survive database outages and restart; operation UUIDs make publication idempotent.
Incremental `.capture` checkpoints recover validated, confirmed frames from unfinished
`.tmp` files as `partial` operations. Frames not yet flushed can be lost on a crash.
Files without a usable checkpoint are retained and logged for investigation. Filesystem, database
and Minecraft world saving are not one transaction: no crash-atomic world/log guarantee
is claimed. `partial` operations retain confirmed changes without claiming completion.

Retention uses `storage.should-expire` and `storage.expire-records`. A read lease
prevents deletion during queries; deleting operations are hidden, then files and
indexes are removed with retries. Historical SQL records are preserved by the
additive `Captured`/`CaptureSequence` column migration.

## Build and verification

WE and FAWE are compile-only server dependencies and are not bundled into Prism.
Install the assembled `build/libs/prism-all.jar` on the server.
Place the two jars above in ignored `lib/`, or pass their absolute paths:

```powershell
./gradlew.bat -PworldEditJar="D:/path/worldedit-forge-mc1.12.2-6.1.10-dist.jar" -PfaweJar="D:/path/FastAsyncWorldEdit-forge112-19.11.13-5505943-1282-22.3.5.jar" test checkstyleMain checkstyleTest assemble
```

Automated tests cover file checksums, lossless NBT, dense million-block indexing,
precise spatial filtering, recovery and SQL retry, mixed ordering/grouping/limits,
and the actual legacy FAWE callback API. Regression tests run both native default
and weak-reference queue maps, including `383:8`, and verify publication before
the journal is closed. The synthetic storage benchmark is not an
in-game FAWE throughput benchmark. Production compatibility requires a disposable
SpongeForge server test of masks, cancellation, fast mode, undo/redo, simultaneous
edits, mod tiles and rollback/restore, with FAWE editing time and tick latency measured.
