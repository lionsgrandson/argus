# Security notes

## Cryptography

Audio and status packets use AES-256-GCM with a 32-byte random key from `SecureRandom`. The key is contained only in the BM2 pairing code and local encrypted app storage; it is never sent to the relay.

Each sender service instance uses a random 64-bit session value. GCM nonces are 96 bits: the full 64-bit session plus the low 32 bits of the monotonically increasing packet sequence. The codec refuses to continue if the sequence exceeds the 32-bit nonce counter space. At 50 audio packets/sec this limit is far beyond a normal service session.

The authenticated packet header includes protocol version, packet type, session and full 64-bit sequence. Modified ciphertext/header values fail GCM authentication and are dropped.

## Relay authentication

The BM2 pairing code contains a separate random 128-bit relay token. The Android client sends that token as an HTTPS/WSS `Authorization: Bearer` header. The relay hashes it using SHA-256 and keeps only the hash while the ephemeral room is active.

The relay never receives the AES key, and binary payloads are opaque ciphertext.

## TLS

The app accepts only `wss://` relay URLs. Its raw `SSLSocket` enables HTTPS endpoint identification, so a valid certificate for the requested hostname is required.

## At-rest secrets

Saved BM2 pairing credentials are encrypted with AES-GCM using a non-exportable AES-256 key generated in `AndroidKeyStore`. Android backup is disabled for the application.

## Threat model limitations

This design does not protect against a compromised/rooted endpoint, malicious accessibility service with sufficient access, someone who obtains the pairing code, or a hostile relay that intentionally disrupts availability. A malicious relay can drop/reorder traffic or deny service, but it cannot decrypt valid E2EE packets without the pairing key.

The relay host can observe IP addresses and connection metadata. This is not anonymity software.

## Key rotation

Generate a new pairing code on the baby phone whenever you suspect the old code was exposed. This rotates room ID, relay token and AES key in one action.
