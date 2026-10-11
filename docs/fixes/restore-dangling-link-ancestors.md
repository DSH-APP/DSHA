# Restore links whose target parent directories are missing

Full v5 archive inspection could pass but Confirm restore fail with
`PARENT_LINK_OR_MISSING` during `NativeRestorePlan.buildCandidates`. The link
restore loop called the strict filesystem child resolver before checking whether
the link's destination existed. A missing intermediate directory therefore
aborted the entire candidate instead of retaining the unresolved link metadata.

The link destination lookup now returns unresolved when an intermediate or leaf
destination is missing. The existing metadata quarantine keeps that record. It
does not create destination directories, follow symlinks, change active data, or
weaken the shared filesystem resolver. Non-directory and symlink parents still
reject restoration; cancellation, limits, checksum validation and link cycle
checks are unchanged.

Validation: eight `NativeRestorePlanTest` regressions passed with no skipped
tests, including deep dangling links, non-directory parents, symlink parent
rejection using an explicit host adapter, and available link restoration.
An independently exported 1285-record K80 archive reproduced the original
failure at the link destination lookup. With this change its private candidate
completed with 3 groups and 59 mapped roots. Source contents were kept private.
All payload writes, parent checks, archive validation and restored checksums
used production code and actual files. Windows lacked symbolic-link creation
privileges, so one final link leaf was explicitly modeled in that local probe;
Android restore remains the platform validation.

This repair is a separate local change from the Android 17 virtual-screen PR.
It has not been submitted upstream or represented as an official signed build.
