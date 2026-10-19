# Security reports

Do not post real transcripts, recordings, signing keys, credentials or personal logs in a public issue. Share a minimal reproduction and the optional privacy-limited diagnostics export. Use GitHub private vulnerability reporting after the repository owner enables it; if it is not enabled, ask the owner for a private reporting route without including exploit data publicly.

Model downloads require HTTPS, catalog-pinned file/archive size and SHA-256, and atomic installation. File imports use the same hash validation. Catalog changes must be reviewed together with upstream model provenance/licenses. Mirrors are untrusted transport sources; never relax checksums to make a download work. No third-party plugin loading or arbitrary model execution is exposed.

Only the launcher Activity is exported. Services and settings/model Activities are private. The app has no Accessibility Service and no automatic network telemetry. Captions stay in memory unless exported. Native parsers/runtimes remain part of the attack surface; keep pinned dependency revisions under review.
