# Managed ADB virtual-screen launch diagnostics

An installed core can start successfully while the app's managed ADB launch
fails. The manager previously recognized only `DSHA_VSCREEN_ERROR` output, so
environment occupation, policy rejection, missing pairing dependencies and
bridge failures all appeared as `VSCREEN_START_RESULT_UNKNOWN`.

The existing bootstrap error reader now maps recognized failure markers to
fixed codes. The bundled ADB client reports START versus COMMIT and a fixed
reason for bridge authentication, ticket rejection, channel availability,
canonical APK path checks, timeout, HTTP failure, connection failure or invalid
response. No exception message, source command, token or original response is
copied into these codes. Existing manager diagnostics and returned errors show
the code automatically. The core handshake continues to ignore ordinary ROM
output and accepts only its exact bootstrap marker.

Authentication, the single-use ticket, exact command verification and the
no-replay rule are unchanged. A lost COMMIT response still raises execution
unknown and never sends the ADB command. The app's normal signed asset comparison
and synchronization publish the updated bundled script.

Eight bootstrap JUnit tests and seven ADB launch host tests passed, including
secret-bearing failures, auth/ticket rejections, START/COMMIT differentiation,
exact successful handshake and no remote send after unknown commit.
These diagnostics are included in the Android 17 virtual-screen repair PR;
installed-app acceptance and release limitations are recorded in
`android17-vscreen-k80.md`.
