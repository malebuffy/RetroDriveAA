# RetroDrive WebSocket Relay (Cloudflare Worker + Durable Object)

## Files
- `wrangler.toml`
- `src/index.js`
- `package.json`

## What this does
- Exposes `/ws?session=<id>&role=phone|car`
- Uses one Durable Object room per `session`
- Forwards binary/text frames from phone -> car and car -> phone

## Deploy
1. Install Node.js LTS
2. In this folder run:
   - `npm install`
   - `npx wrangler login`
   - `npx wrangler deploy`

## Test
Open two terminals:

Terminal A:
- `npx wscat -c "wss://<your-worker>.workers.dev/ws?session=test&role=phone"`

Terminal B:
- `npx wscat -c "wss://<your-worker>.workers.dev/ws?session=test&role=car"`

Type in either terminal and you should see it in the other.

## Notes
- If one side reconnects, the previous socket for that role is replaced.
- This is a minimal relay; add auth/token checks before production use.
