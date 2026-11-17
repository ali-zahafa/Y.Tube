# Playback recovery preview validation

This branch is a CI-only validation branch for the playback recovery changes currently on `main`.

## Required device checks before calling the issue fixed

- Open each reported Made-for-Kids test video from a cold start.
- Confirm whether the direct player renders its first frame.
- If direct extraction is refused, confirm the recovery state advances through all configured routes and then exposes the YouTube website player instead of remaining on a stale error overlay.
- Verify the website player controls, including fullscreen, on a physical Android device.
- Run a continuous playback test long enough to catch the reported 50–60 second HTTP 403 and 7–9 second rebuffer pattern.

A green CI build validates compilation and unit tests only. It does not replace these network- and device-dependent playback checks.
