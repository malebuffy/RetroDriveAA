# RetroDrive Save States (MVP)

## What is implemented

- Per-game save slots: **up to 5 states**.
- **Auto-save on exit** from the in-game red `X` button.
- Launcher **Resume** button next to Start.
- Resume button is disabled when no valid state exists.
- State file includes header: `magic + version + gameId`.
- Atomic writes: write `.tmp` then rename/replace.
- Load picker shows 5 slots with thumbnail previews and per-slot delete.

## State file location scheme

Files are stored under:

- `context.filesDir/savestates/`

File name format:

- `<sanitizedGameId>_<fnv1a64(gameId)>_s<slot>.state`
- `<sanitizedGameId>_<fnv1a64(gameId)>_s<slot>.png` (thumbnail)

`gameId` is usually the selected game folder name, and `__browse__` for the generic DOS browse mode.

## Compatibility / versioning policy

- Global format version is currently `3`.
- Header validation checks:
  - magic (`RDSVSTA1`)
- version (`1`, `2`, or `3`, where `3` is current)
  - matching `gameId`
- If header validation fails, Resume is disabled and loading is rejected.

## Current module coverage

- CPU core state (register blocks + decoder selection)
- Main RAM snapshot + A20 line
- VGA state payload (video memory, fast renderer cache memory, VGA font RAM, VGA latch)
- VGA palette payload (DAC + attribute palette state, reapplied to renderer on load)

### Version notes

- Version `1` states are still loadable (CPU + RAM only).
- Version `2` states are used for all new saves and include VGA payload for games that only redraw moving regions.
- Version `3` states are used for all new saves and additionally preserve active palette state (for scene tint/time-of-day correctness).

## Slot rules

- Maximum of 5 saves per game.
- New save uses the first empty slot.
- If all 5 slots are used, user must delete one slot before saving again.
- Load flow: open slot modal, choose slot to load, or delete slot from the same modal.

> This is an emulator-level foundation with deterministic module ordering and module-versioned payloads. More subsystems can be added incrementally to improve correctness for all games/devices.

## Launcher behavior

- **Start**: normal DOSBox boot for selected game.
- **Resume**: starts DOSBox and requests immediate load of that game’s last-state.
- **Delete game**: removes game profile and its state file.

## Manual test steps

1. Start a game from launcher (`Start`).
2. Play for ~30 seconds.
3. Press in-game red `X` exit button.
4. Return to launcher and verify `Resume` is enabled.
5. Press `Resume` and verify session restores.
6. For a game with no state, verify `Resume` is disabled.
7. Corrupt the `.state` file and verify `Resume` is disabled.
