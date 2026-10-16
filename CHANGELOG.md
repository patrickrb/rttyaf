# Changelog

Internal-track builds are named `<version>-dev.<CI run>`; the short tester-facing
text for each release lives in `distribution/whatsnew/` (shown in Play's
"What's new").

## 1.2.0 — 2026-10-16

### Fixed
- **TX did nothing with a USB sound card.** RTTY TX refused the "USB direct"
  audio route (CM108-style codecs), so a rig wired that way never keyed. It now
  uses the same USB path as the rest of the app, and STOP cancels it within
  tens of milliseconds, even while the device is still opening. (#14)
- **Refusals were invisible.** Messages such as "TX audio dropped" or "can't
  change band while transmitting" now appear in a banner above the tab bar,
  announced politely to TalkBack. Tapping TX with nothing staged says so. (#14)
- The app no longer calls itself FT8AF anywhere: exit dialog, notifications,
  ADIF program ID, export folder and every translated string. (#11, #15)

### Added
- **RTTY Mode settings**: baud (45.45 / 50 / 75), shift (170 / 200 / 425 / 850),
  tone presets (2125/2295 high, 1275 low, custom), rig sideband (LSB standard /
  USB), USOS, squelch, and whether AFC and NET start on. AFC now defaults to
  **off**. (#12)
- **XY tuning scope** beside the waterfall, a working **NET** switch (off keeps
  TX on your saved tones), and tap the MARK/SPACE readout to snap back to them
  after AFC or tap-to-tune moved RX. (#13)
- SWR lockout and ALC auto-level now protect RTTY overs and the tune carrier.
  (#18)

### Removed
- The inherited **FT8 engine**: it was decoding FT8 and uploading FT8 spots to
  PSK Reporter in the background while you used the RTTY screens. The FT8
  decoder, encoder and sequencer, the `ft8_lib` native code, and the legacy FT8
  screens are gone (~77k lines); the native library is now just the USB-audio
  bridge. (#16, #17, #18)

### Notes for testers
- Settings, the theme and saved backups carry over; backups made by the old
  build still import.
- If you pinned the app to your home screen, re-add the icon once (the launcher
  activity was renamed).
