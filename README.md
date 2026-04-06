# RetroDriveAA

RetroDriveAA is a DOSBox-based Android Auto project for running and controlling classic DOS games from the car display.

## Current Setup

- Local game import is handled on-device through the Android app.
- The old remote web upload flow has been removed.
- Phone controller support is handled through a Cloudflare Worker WebSocket relay.

## Project Structure

- `app/` Android application, native DOSBox integration, controller flow, and local import flow
- `cloudflare-worker/` Cloudflare Worker and Durable Object relay for phone controller sessions
- `src/` native DOSBox sources and platform code

## Build

From the repository root:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Controller Relay Docs

Detailed Cloudflare Worker deployment and controller relay instructions are in `cloudflare-worker/README.md`.

## Notes

- The worker is used only for controller WebSocket relay traffic.
- Game upload now stays local to the phone and app flow.
