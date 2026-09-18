# Sync protocol

_Written in Phase 5._ Design summary lives in BRIEF.md §3.6 and §8: append-only change log, server-assigned `seq`, set-union for `Review`, FSRS state recomputed from reviews, last-writer-wins by `(ts, deviceId)` for everything else, tombstones for deletes.
