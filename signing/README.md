# Development signing

`local-bridge-dev.keystore` is intentionally committed and is used only for local/debug APKs so builds from different CI runners can update one another on test devices.

It is not a release signing key and must never be reused for production/release signing.
