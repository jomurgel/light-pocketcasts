# Changelog

Every build that goes to Light gets an entry here, newest first. The number in parentheses is the `versionCode` in `tool/lighttool.toml`, which has to go up by one with each build. Each entry has a matching tag, `pocketcasts-v<version>`.

## 0.1.0 (build 1) — 2026-10-12

The first version. It does the thing I wanted it for: sign in on the phone and carry on with whatever I was listening to elsewhere.

- Sign in with a Pocket Casts email and password
- Subscribed podcasts and their episodes, cached for offline browsing
- Streaming, plus downloads for offline listening
- Playback that keeps going with the screen off or the tool closed
- Listening position synced both ways
- Up Next synced both ways: play next, play last, remove, move to top
- Archive and star
- Play/pause, 15-second skips, playback speed
- Signing out removes the library, queue, unsynced changes and downloads from the phone

Known limits: no Google or Apple sign-in, no search or subscribing from the phone, and changing Up Next while streaming pauses the audio for a second or two while the player reloads.
