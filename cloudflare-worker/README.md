# RetroDrive WebSocket Relay

This Cloudflare Worker and Durable Object pair handles only the phone controller WebSocket relay for RetroDriveAA. It does not handle game uploads, PHP relay endpoints, or file storage.

## Files
- `wrangler.toml`
- `src/index.js`
- `package.json`

## What It Does
- Exposes `/ws?session=<id>&role=phone|car`
- Uses one Durable Object room per `session`
- Forwards binary and text frames from phone to car and car to phone
- Replaces the older socket if the same role reconnects for a session

## Controller Flow
1. The Android app starts a DOS session and generates a session id.
2. The app connects to the Worker as `role=car`.
3. The app shows a QR code for the hosted controller page.
4. The phone opens the hosted controller page.
5. The page connects to the Worker as `role=phone`.
6. The Durable Object relays frames between the two sockets.

## Prerequisites
- Node.js LTS
- A Cloudflare account
- Wrangler via npm

## Install And Login
1. Open a terminal in `cloudflare-worker`.
2. Run `npm install`.
3. Run `npx wrangler login`.

## Configuration
Current defaults:
- Worker name: `retrodrive-ws-relay`
- Durable Object binding: `RELAY`
- Durable Object class: `RelayRoom`

If you rename the Durable Object class or binding, update both:
- `wrangler.toml`
- `src/index.js`

## Deploy
1. In `cloudflare-worker`, run `npx wrangler deploy`.
2. Note the deployed Worker URL, for example:
    `wss://retrodrive-ws-relay.<your-subdomain>.workers.dev/ws`

## Verify
1. Open the root Worker URL in a browser.
2. It should return `retrodrive relay online`.
3. Test with two WebSocket clients.

Example with `wscat`:

Terminal A:
`npx wscat -c "wss://<your-worker>.workers.dev/ws?session=test&role=car"`

Terminal B:
`npx wscat -c "wss://<your-worker>.workers.dev/ws?session=test&role=phone"`

If you send a message in one terminal, it should appear in the other.

## Hosted Controller Page
- The Worker relays WebSocket traffic only.
- The HTML controller UI is hosted separately.
- The app points at that page through `CONTROLLER_WEB_BASE_URL`.
- The page expects these query parameters:
   - `session=<generated-session-id>`
   - `ws=<base-worker-websocket-url>`

The page then builds the phone-side connection URL as:
`<ws>?session=<id>&role=phone`

## Android App Integration
Update these values in `app/build.gradle.kts` if deployment URLs change:
- `CONTROLLER_WS_BASE_URL`
- `CONTROLLER_WEB_BASE_URL`

Current examples:
- `CONTROLLER_WS_BASE_URL = wss://retrodrive.antoniadis.workers.dev/ws`
- `CONTROLLER_WEB_BASE_URL = https://retrodrive.code-odyssey.com/controller.html`

## Common Problems
- `426 Expected WebSocket`
   The client hit `/ws` without a WebSocket upgrade request.

- `400 Missing session or role`
   The request URL is missing required query parameters.

- `400 Invalid role`
   Only `phone` and `car` are accepted.

- QR page loads but controller does not connect
   Check that `CONTROLLER_WEB_BASE_URL` is reachable from the phone.
   Check that the page passes `ws` and `session` into the WebSocket URL.
   Check that the Worker URL ends with `/ws`.

- Connection replacements
   The same role is connecting multiple times for one session.
   The Worker intentionally replaces the older socket.

## Security Notes
- Session ids are not authentication.
- Anyone with a valid session id can attempt to connect.
- For production hardening, add a signed token or short-lived auth layer.
- Consider origin validation and rate limiting at the Worker layer.

## Useful Files
- `cloudflare-worker/src/index.js`
- `cloudflare-worker/wrangler.toml`
- `app/src/main/java/com/dosbox/emu/WifiControllerServer.java`
- `app/src/main/assets/www/controller.html`
- `app/build.gradle.kts`
