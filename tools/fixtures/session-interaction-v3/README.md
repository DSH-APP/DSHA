# Preserved session interaction V3 inputs

The recipe and helper are exact copies of the production inputs saved before
the 2026-10-22 UI latency changes. `provenance.json` pins both byte hashes and
the original rc.2 workspace client module hash. The old managed JavaScript is
derived by applying these actual historical inputs to those exact canonical
bytes, including the historical helper prepend. Its SHA-256 was independently
matched to the retained `managed-df2d725deb59` workspace client module:
`3cf9260587e730f4624dc2777e78e10fe9032522eb226df3a0197bcf3d2a3232`.

No verified V2 recipe/helper was available in the supplied snapshots. The test's
V2 marker case is explicitly synthetic and must not be reported as an actual
historical V2 upgrade test. The fixture contains no device, profile, session,
credentials, conversation, or workspace data.
