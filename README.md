<div align="center">

# SimReset

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/SimReset?style=flat-square&label=latest&color=brightgreen)](https://github.com/Yukovsky/SimReset/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.172+-orange?style=flat-square)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-lightgrey?style=flat-square)](LICENSE)

**Server-side admin toolkit for force-resetting stuck sub-levels on NeoForge 1.21.1.**

Adds `/sable disassemble` and `/sable reassemble` — instantly tear down or rebuild [Sable](https://maven.ryanhcode.dev/) sub-level structures (the physics sub-worlds behind [Aeronautics](https://github.com/Creators-of-Aeronautics/Simulated-Project) ships and contraptions) without waiting for physics to settle.

| Links | |
|---|---|
| Issues | [github.com/Yukovsky/SimReset/issues](https://github.com/Yukovsky/SimReset/issues) |
| Releases | [github.com/Yukovsky/SimReset/releases](https://github.com/Yukovsky/SimReset/releases) |

</div>

---

## Features

| Command | What it does |
|---|---|
| `/sable disassemble all` | Instantly disassembles every sub-level in the current dimension |
| `/sable disassemble uuid <uuid>` | Disassembles a single sub-level by its UUID |
| `/sable disassemble name <name>` | Disassembles a single sub-level by its display name |
| `/sable reassemble all` | Disassembles, then reassembles every sub-level one tick later |
| `/sable reassemble uuid <uuid>` | Disassembles and reassembles a single sub-level by UUID |
| `/sable reassemble name <name>` | Disassembles and reassembles a single sub-level by display name |

- **Disassemble is instant** — no waiting for physics alignment.
- **Reassemble** disassembles first, then rebuilds through the sub-level's `PhysicsAssembler` one tick later, preserving its display name.
- **Tab-completion** for both UUID and name arguments, sourced from currently loaded sub-levels.
- **Scans unloaded sub-levels** directly from storage, not just the ones currently in memory.
- **Version-aware reflection** — works across Sable 1.x/2.x and Aeronautics 1.2.x/1.3.x without needing separate builds.
- Sub-levels without a primary assembler are skipped with a warning instead of failing the whole batch.

Built for server operators who need to clear a sub-level stuck mid-assembly, corrupted by a crash, or duplicated by a desync — a quick reset beats restoring from backup.

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.172 or later |
| Java | 21 |
| Side | Server only |
| Dependencies | [Sable](https://maven.ryanhcode.dev/) 1.2.2+, [Simulated](https://github.com/Creators-of-Aeronautics/Simulated-Project) 1.2.1+ (bundled with Aeronautics) |

---

## Installation

1. Download the latest jar from [Releases](https://github.com/Yukovsky/SimReset/releases).
2. Place it in the `mods/` folder of your NeoForge server, alongside Sable and Aeronautics.
3. Start the server — no configuration required.

---

## Commands

Permission node: `simreset.command` (default: OP level 2, permission 2).

```
/sable disassemble all
/sable disassemble uuid 550e8400-e29b-41d4-a716-446655440000
/sable disassemble name "MyShip"

/sable reassemble all
/sable reassemble uuid 550e8400-e29b-41d4-a716-446655440000
/sable reassemble name "MyShip"
```

A sub-level's UUID can be found via `/sable storage find_all_sub_levels` or `/sable info`. Its display name is set in the assembler's GUI and can be looked up with `/sable storage find <name>`.

---

## Building from Source

SimReset compiles against Sable and Aeronautics/Simulated as local `compileOnly` jars — they aren't published on a public Maven repository, so they must be provided manually.

1. Clone the repository:
   ```bash
   git clone https://github.com/Yukovsky/SimReset.git
   cd SimReset
   ```
2. Copy the following jars into `libs/` (from a server's `mods/` folder, or your own build of each project):
   - `sable-neoforge-<version>.jar`
   - `aeronautics-neoforge-<version>.jar` or `create-aeronautics-bundled-<version>.jar` (includes Simulated)
3. Build:
   ```bash
   ./gradlew build
   ```

Output jar: `build/libs/simreset-<version>.jar`. Requires Java 21.

---

## License

[Apache License 2.0](LICENSE)
