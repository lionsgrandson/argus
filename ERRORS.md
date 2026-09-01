# ARGUS error codes

ARGUS version 3.2 introduces persistent error reporting. The last error is stored locally with its code, role, message, technical detail, timestamp, and repeat count. Connection and runtime errors also create an Android notification.

## Pairing and setup

| Code | Meaning |
| --- | --- |
| E100 | Pairing or relay details are missing |
| E101 | Pairing code is empty or invalid |
| E102 | Pairing code or protocol belongs to the reset/old generation |
| E103 | ARGUS could not generate pairing credentials |

## Relay and network

| Code | Meaning |
| --- | --- |
| E200 | Relay URL/configuration is invalid |
| E201 | DNS lookup failed |
| E202 | Relay connection timed out |
| E203 | TLS/security handshake failed |
| E204 | Relay returned an invalid/unexpected HTTP/WebSocket response |
| E205 | Relay rejected the pairing credentials |
| E206 | Relay host could not be reached / connection refused |
| E207 | Network socket closed or other socket I/O failed |
| E208 | Relay explicitly closed the WebSocket |
| E209 | Incoming WebSocket frame was too large |
| E210 | Relay rate limit was hit |
| E211 | Relay could not forward a frame to the peer |
| E212 | Cloudflare relay WebSocket error |

## Audio and camera

| Code | Meaning |
| --- | --- |
| E301 | Child microphone permission is missing |
| E302 | Child microphone could not initialize or continue recording |
| E303 | Child camera permission is missing |
| E304 | Parent audio playback could not initialize |
| E305 | Child camera disconnected |
| E306 | Child camera is unavailable |
| E307 | Child camera capture session/setup failed |
| E308 | Child camera capture failed |
| E309 | Camera JPEG could not be read or was too large |
| E310 | Camera screen orientation could not be read |

## Stream and encrypted data

| Code | Meaning |
| --- | --- |
| E401 | Encryption/decryption or encrypted packet processing failed |
| E402 | Stream control command failed |
| E403 | Child status/battery message failed |
| E404 | Background WiFi lock could not be acquired |
| E405 | Phones are connected but expected media stopped arriving |
| E406 | Parent disconnect alarm tone could not play |

## Unexpected

| Code | Meaning |
| --- | --- |
| E500 | Unexpected application or connection error |

When reporting a field issue, send the code plus the technical detail shown in the ARGUS error notification. Example: `E203 SSLHandshakeException: ...`.
