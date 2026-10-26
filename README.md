# Sovereign SMP v1.3 — Echo Scythe, Artifact I / VII

## Artifact system
- **Echo Scythe of the Deep** (netherite sword) is Legendary Artifact **I / VII**.
- Sovereign Mace of Gravity is a unique **Special Weapon**, NOT one of the seven artifacts.
- Existing Mace ownership/token in `plugins/SovereignSMP/artifacts.yml` are preserved.
- Each item has its own one-of-a-kind token and owner, claimed via pickup or `/sovereign claim`.

## Echo Scythe controls
- Right click: Echo Pulse (18-block revealing wave, Glowing).
- Shift + right click: Silence of the Deep, grants 7 seconds of **True Invisibility** (complete client-side player entity hidden, including armor and held items).
- F (Swap Hands): Warden's Wrath, three sonic sculk waves from your aim.
- Passive: Sculk Awareness detects fast-moving nearby players.

True Invisibility hides the entire player entity using Paper `hideEntity`. No particles are emitted *during* the stealth effect by the plugin, and the player is set silent for entity sounds. Other gameplay sounds and environmental effects may still reveal activity. Server-side attacks and combat remain possible. It ends on expiry, death, logout, and plugin shutdown.

## Commands
- OP: `/sovereign mint scythe <player>` or `/sovereign recover scythe <player>`
- OP: `/sovereign mint mace <player>` or `/sovereign recover mace <player>`
- Everyone: `/sovereign status`, `/sovereign claim scythe`, `/sovereign claim mace`, `/sovereign cooldowns`, `/sovereign restyle`
- OP: `/sovereign tooltip <on|off|status>`

## Tooltip packs
Pure RGB text gradients work without a resource pack. For sculk/royal tooltip **backgrounds**, enable the optional standalone tooltip resource pack. It modifies tooltip frames, not item sprites. Run `/sovereign tooltip on`, `/sovereign restyle`.

## Build
Paper 1.21.11, Java 21, Gradle 8.14 GitHub Actions. `./gradlew build` if wrapper available or `gradle build`. Full Paper compilation and Minecraft integration testing required.
