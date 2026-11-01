# Input fixture provenance

- `linda-share.txt`: verbatim share text provided by the user in this chat. This tests extraction only, not current online availability.
- `screenshot-shares.txt`: manually transcribed from the user's two phone screenshots on 2026-10-03. The character before `WJML4` in the second URL is visually ambiguous (`l` / `I`); this fixture chooses lowercase `l` solely to exercise opaque token preservation. It is **not** a verified live URL. Copy-token punctuation is also a transcription, not network evidence.
- Additional values embedded in the test classes are explicitly synthetic. No test performs an online request or assumes a video is still available.
