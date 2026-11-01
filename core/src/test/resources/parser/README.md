# Parser fixture provenance

All files named `*-synthetic.html` are authored, offline contract fixtures. Their IDs, authors,
titles, and CDN URLs are fictional. They are never requested over the network or used by the
application at runtime. Passing them demonstrates parser behavior, **not current live Douyin
media extraction**.

`empty-router-sanitized.html` is a sanitized real **failure** fixture observed on 2026-10-03
from an anonymous public iesdouyin share-page response. Its JSON keys, nesting, null layout,
empty error state, and experiment values were preserved. Personal/device/server/query values
were cleared, and the item ID was replaced with fictional `7000000000000000001`. Surrounding
scripts/markup were removed. It intentionally has no media addresses and must never produce
successful media extraction. See `docs/PHASE_3_RESEARCH.md` and `docs/phase3-live-check.json`.

The positive fixtures cover `_ROUTER_DATA`, percent-encoded `RENDER_DATA`, `SIGI_STATE`,
application JSON/Next hydration state, JSON-LD `VideoObject`, and canonical-bound HTML video
sources. Additional malformed, ambiguous, hostile, and oversized cases are constructed in tests.
