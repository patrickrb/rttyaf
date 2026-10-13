# RTTYAF design source

`RTTYAF.dc.html` is the Claude Design clickable prototype (imported from
claude.ai/design project 2647faca-4453-4d59-8fcb-ad8acdb96e28). It is the
visual/interaction spec for the app. Palette + type (Inter + Geist Mono) are
lifted from FT8AF so RTTYAF reads as the same family.

Screens: Operate · Contest · Macros · Log · Settings → (Radio & Audio,
Logging & Sync, RTTY Mode). Bottom nav has 5 items.

Defaults: MYCALL KS3CKC · FN20 · 20m RTTY @ 14.084 · 45.45 Bd / 170 Hz shift.

## Spots (RBN + PSK Reporter)

Design canvas: https://claude.ai/artifact/Kmjd5wRSQLE5B2NBPxp55h (private to the
project owner; share from the page's Share menu). Five artboards: Operate with
the band rail and waterfall spot tags, the Spots sheet, the tap-to-go banner,
the cross-band confirmation, and the rail's empty/connecting states.

Principles the implementation follows:

- **See before tuning.** The band rail shows the current band's RTTY segment
  with one pip per spotted station (RBN green, PSK grey; height = freshness)
  and the rig's 3 kHz passband as a window. Spots inside the passband also
  appear as callsign tags on the waterfall at their audio offset.
- **One tap to go.** Pip, tag or sheet row → `SpotService.go`: in-passband
  spots just retune the decoder; same-band spots QSY the dial so the mark tone
  lands at 2125 Hz; cross-band spots ask first (antenna/ATU safety).
- **Ranked for "what next".** Fresh RBN spots first, then by recency.
