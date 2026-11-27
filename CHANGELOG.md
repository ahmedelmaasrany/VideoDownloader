# Changelog

## [1.1.0] - 2026-11-27

### Added
- Platform-aware route learning with normalized statistics for 15 selected platform groups.
- Startup storage maintenance for abandoned temporary download workspaces.
- Download history retention that keeps the newest 100 successful or failed records.
- Process-wide download-engine initialization and persisted onboarding completion.
- QuickJS runtime configuration for yt-dlp JavaScript execution.

### Improved
- Route estimates use observed network throughput, startup time, encoding speed, save speed, and retry history.
- Persisted queue metadata includes additional video and audio format information.
- Production builds target ARM64 only, while Development builds retain ARM64 and x86_64.
- Release code and resource optimization.

### Notes
- Platform learning groups do not guarantee extraction support for every URL. Actual extraction depends on yt-dlp and platform availability.
- The Production APK produced by CI is unsigned until release signing is configured.
