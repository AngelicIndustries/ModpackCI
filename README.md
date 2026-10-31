# ModpackCI

Build, test and release Minecraft 1.7.10 modpacks made with [packwiz](https://github.com/packwiz/packwiz), on Java 8 and on Java 25 through [lwjgl3ify](https://github.com/GTNewHorizons/lwjgl3ify).

## Start a pack

Download `starter.zip` from the [latest release](https://github.com/AngelicIndustries/ModpackCI/releases/latest) and unpack it into a new repo. Run `./modpackw help` (`modpackw.bat` on Windows). The wrapper downloads Java and ModpackCI on first use; nothing else needs to be installed.

| Task | What it does |
|---|---|
| `runClient`, `runClient25` | Play the pack on Java 8 or 25, in `run/`, no launcher needed |
| `runServer`, `runServer25` | Run a local server in `run/` |
| `packwiz <args>` | packwiz, bundled |
| `build`, `build<Type>` | Build release files into `build/` |
| `smokeServer[25]`, `smokeClient[25]` | The CI tests: start a server, or open a world in a client under Xvfb (Linux) |
| `updateModpackCI` | Move the repo to the latest ModpackCI |

The Java 8 runs leave lwjgl3ify out. The Java 25 runs add lwjgl3ify and UniMixins if the pack doesn't list them.

## Settings

`modpack.ini` (committed) lists project IDs and which files go where. `modpack-local.ini` (gitignored) overrides it on one machine and also sets Java and packwiz paths. Environment variables override both; the names are in `modpack.ini`'s comments and `modpack-local.ini.example`.

`localMods` in `modpack-local.ini` adds mods you're working on to the run tasks: Gradle projects, built first, or jars. One with the same mod ID as a pack mod replaces it for the run.

`modernJavaMods` in `modpack.ini` names pack mods that need lwjgl3ify, so Java 17 or newer (their `.pw.toml` names without the extension, comma-separated). Java 8 runs, tests and `prism-java8` zips leave them out, and an `.mrpack` offers them as optional unless the pack lists lwjgl3ify.

`java8JvmArgs` and `java25JvmArgs` in `modpack.ini` add JVM arguments (for example `-XX:+UseZGC` on Java 25) to the runs, tests and Prism zips of that Java version. An `.mrpack` can't carry JVM arguments.

Release types: `mrpack-slim` (mods download from their original hosts), `mrpack-fat` (everything inside), `prism-java8` and `prism-java17` (Prism instance zips), `curseforge-zip`.

| Destination | Types key | Project key | Token |
|---|---|---|---|
| GitHub | `githubReleaseTypes` (`GH_RELEASE_TYPES`) | | provided by Actions |
| 67minecraft.com | `sixSevenReleaseTypes` | `sixSevenProjectId` | `SIX_SEVEN_TOKEN` |
| Modrinth | `modrinthReleaseTypes` | `modrinthProjectId` | `MODRINTH_TOKEN` |
| CurseForge | `curseForgeReleaseTypes` | `curseForgeProjectId` | `CURSEFORGE_TOKEN` |

## CI and releases

The starter's `ci.yml` calls this repo's workflow at `@v1`. Every push runs the smoke tests for each Java version the configured release files run on. Pushing a `v*` tag also releases:

- The tag (minus `v`) becomes the pack version.
- Tags containing `snapshot` only make a GitHub prerelease.
- Release notes come from `.changelogs/<tag>.md`, or GitHub generates them.
- A commit message containing `ci-skip` skips CI. Tags still release.

`modpackw.properties` pins the exact ModpackCI version, so local runs and CI use the same one. Every run checks for a newer release once a day; `updateModpackCI` updates the wrapper, the pinned version, new `modpack.ini` keys and the workflow's major version.
