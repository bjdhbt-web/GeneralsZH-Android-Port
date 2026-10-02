# Abodeh Play subscriptions

The subscription edition uses a WordPress REST backend at `https://syscomx.net/wp-json/abodeh-play/v1`.

The Android launcher authenticates with username/password, derives a device hash from Android ID plus the app signing certificate, and signs a one-time server challenge with an ECDSA P-256 key stored in Android Keystore. The server binds the account to the first device hash. Sign-out revokes only the session; device binding remains until an administrator resets it.

The backend WordPress plugin is deployed separately and is not committed with game assets.
