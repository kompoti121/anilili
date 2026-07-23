# Miruro pipe protocol (reverse-engineered)

This is the on-device contract the app speaks to Miruro. Verified against **both** the live
`www.miruro.bz` client bundle and the open-source `MiruroAPI` (MIT) reference implementation —
they agree byte-for-byte. Keep this doc in sync if the protocol changes.

## Endpoints

| Purpose  | Transport                                            |
|----------|------------------------------------------------------|
| Metadata | AniList GraphQL — `POST https://graphql.anilist.co`  |
| Episodes | Pipe — `GET {origin}/api/secure/pipe?e=<b64url>`     |
| Sources  | Pipe — `GET {origin}/api/secure/pipe?e=<b64url>`     |

`{origin}` rotates over the mirrors (all serve the same pipe):
`https://www.miruro.to`, `https://www.miruro.bz`, `https://www.miruro.ru`, `https://www.miruro.tv`.

## Request envelope

```
payload = { "path": <string>, "method": "GET", "query": <object>, "body": null }
e       = base64url( utf8( JSON.stringify(payload) ) )     // no '=' padding
GET {origin}/api/secure/pipe?e={e}
```

Send browser-ish headers, most importantly `Referer: {origin}/` and a desktop `User-Agent`.
On a phone the request comes from a **residential/mobile IP**, which is what lets it through
Cloudflare (datacenter IPs get 403 — that is why the hosted MiruroAPI can't stream).

### Pipe paths

| path       | query                                                        | returns                    |
|------------|--------------------------------------------------------------|----------------------------|
| `episodes` | `{ anilistId }`                                              | providers → episode lists  |
| `sources`  | `{ episodeId, provider, category, anilistId }`               | streams + subs + skipTimes |

`category` is `"sub"` or `"dub"`. `episodeId` is the episode's raw `id` from the `episodes`
response, transformed as: `episodeId = base64url( translateId(rawId) )` where
`translateId(x) = { d = utf8(base64urlDecode(x)); d.contains(":") ? d : x }`.
For a normal base64url id this round-trips back to `rawId`, so in practice **you can pass the raw
`id` straight through** — the transform only matters for odd providers whose id isn't base64url.

## Response decoding

Read header `x-obfuscated` (`n`), body text `t`:

```
if n is empty        -> JSON.parse(t)
else:
  b = base64urlDecode(pad(t))           // '-'→'+', '_'→'/', pad to %4
  if n == "2": b = b XOR PIPE_OBF_KEY   // key repeats, byte-wise
  json = utf8( gunzip(b) )              // gzip; Android: GZIPInputStream
  JSON.parse(json)
```

`PIPE_OBF_KEY = hex "71951034f8fbcf53d89db52ceb3dc22c"` (16 bytes). This is the value of
`VITE_PIPE_OBF_KEY` shipped publicly in the site's `env2.js`, i.e. not a secret.

## Sources response (what the player consumes)

```jsonc
{
  "streams": [
    { "url": "https://.../uwu.m3u8", "type": "hls", "quality": "1080p",
      "resolution": { "width": 1920, "height": 1080 }, "codec": "h264",
      "audio": "sub", "isActive": true, "referer": "https://.../e/..." },
    { "url": "https://.../e/...", "type": "embed", ... }   // needs a WebView
  ],
  "subtitles": [ { "url": "...vtt", "label": "English", "language": "en" } ], // or "captions"
  "skipTimes": { "intro": {"start":..,"end":..}, "outro": {"start":..,"end":..} }, // or "skip"
  "download": "https://..."
}
```

`download` is a provider-hosted quality picker (often exposing 1080p/720p/480p MP4 links), not
the HLS manifest itself. The app opens it as a separate download option while retaining Media3
for native offline HLS downloads. Match Miruro's client rewrite for legacy AnimePahe links:
`https://pahe.win/...` → `https://orange-leaf-cefa.asd-968.workers.dev/...`.

Playback rules (from the `/providers` capability map):

- **Native HLS providers** — `kiwi, pewe, bonk, bee, ally, moo, hop` → ExoPlayer/Media3.
  Send `Referer` (the stream's `referer`, else `https://www.miruro.to/`) on manifest + segments.
  `ally` is `cors:true / proxy:false` → cleanest direct playback; good default.
- **Embed/iframe providers** — `nun, bun, twin, cog, telli` (`player:"iframe"`) → WebView.
- If a native stream 403s on segments (hotlink protection beyond referer), fall back to WebView.
  The site's own vault01/02 segment proxy is a **CORS** workaround the browser needs and a native
  player does not, so we skip it in v1.

## Fragility (read before shipping)

The keys/paths above can be rotated by Miruro, and Cloudflare can escalate to a Turnstile
challenge that only a real browser solves. When direct calls start failing, the WebView fallback
(loading the real site) is the durable escape hatch. Treat this file as the thing to re-verify.
