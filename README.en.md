\[Italiano](README.md) · \*\*English\*\*



\# MC Server Host



Native Android app that turns a phone into a host for \*\*Minecraft Java Edition\*\* servers:

Vanilla, Fabric, Forge and NeoForge, with a mod catalog from Modrinth and CurseForge, a real-time

console, a `server.properties` editor, per-session logs, automatic error analysis and connections

from outside through the playit.gg tunnel.



Independent project, not affiliated with Mojang or Microsoft. Starting a server downloads the

official server and means accepting the \[Minecraft EULA](https://www.minecraft.net/eula).



\## Status



| Loader | Status |

|---|---|

| Vanilla | working, tested |

| Fabric | working, tested with real mods and automatic Fabric API installation |

| Forge | working, tested with several mods and several runs |

| NeoForge | working, tested with Create, Flywheel and Ponder |

| Quilt | not supported, removed |



\## The technical problem



A Minecraft server is a Java `.jar`, so the point is not running Java on Android: it is running it

inside a normal app's sandbox. Since Android 10, if the app declares `targetSdk >= 29`, private

storage is mounted `noexec` and no binary downloaded at runtime can be executed. Lowering

`targetSdk` would bypass the block but would prevent publishing on the Play Store.



The app uses two routes.



\*\*Root path\*\*, working and tested. Termux `.deb` packages are downloaded at runtime, extracted

into `files/usr`, the partition is remounted with execute permission and the Java process starts

through `su`.



\*\*Non-root path\*\*, incomplete. The same binaries are prepared at build time inside

`jniLibs/arm64-v8a/`, because Android extracts native libraries into an executable directory.

Every ELF file must be renamed to `lib\*.so`, the only pattern Android extracts, and the mapping

between the original name and the renamed one lives in `assets/native\_binary\_map.json`.



The rest of the infrastructure:



\- `.deb` extraction in pure Kotlin with `commons-compress` and `xz`, because Android has neither

&#x20; `ar` nor `tar`

\- JDK version chosen from the official Mojang manifest, not hardcoded: for each Minecraft version

&#x20; the correct major version is used

\- compatibility libraries downloaded individually from the Termux repositories and copied into the

&#x20; JDK's `lib` folder: `libz.so.1`, `libzip.so`, `libandroid-shmem.so`, `libandroid-spawn.so`

\- for modded loaders the vanilla server is downloaded by the app's own code before launching the

&#x20; installer, instead of relying on the `-downloadMinecraft` flag: that download would happen

&#x20; inside a Java process started under `su`, and it is the most fragile point in the chain

\- `unix\_args.txt` for Forge and NeoForge, `fabric-server-launch.jar` for Fabric, with

&#x20; `win\_args.txt` explicitly excluded, which would otherwise be mistaken for the right one



\## Features



\- server creation with a choice of loader, Minecraft version, loader version and RAM, validated

&#x20; against the device's real memory

\- RAM optimization button before launch

\- mod catalog from Modrinth and CurseForge, with icons, search, details and version choice

\- installation in a foreground service with a notification showing the mod and the real percentage

\- download written to a temporary `.part` file and renamed only once the transfer is complete and

&#x20; verified against `Content-Length`

\- required dependencies installed automatically, and Fabric API installed when creating a Fabric

&#x20; server

\- update check for single mods or in bulk, with version pinning: a pinned mod is not updated until

&#x20; you unpin it

\- real-time console with automatic scrolling and commands you can send to the server

\- `server.properties` editor with structured fields and a free-text mode

\- per-session logs in `app\_logs/` and creation-failure logs in `creation\_logs/`

\- log analyzer that turns errors into readable messages: incompatible mods, missing native library,

&#x20; whitelist, `session.lock`, network problems

\- complete server deletion with root fallback, and preventive termination of residual Java

&#x20; processes before every launch

\- connections from outside with the playit.gg tunnel: agent setup from the phone with no computer,

&#x20; the secret fetched automatically into the Android keystore, or manual entry of the secret

&#x20; obtained from the website or a computer

\- tunnel agent as a foreground service with automatic restart, status notification and a built-in

&#x20; step-by-step guide

\- public server address next to the name in the server screen: tap it to show it in full with a

&#x20; copy button



\## Requirements



\- Android 7.0 or later, `minSdk 24`, `arm64-v8a` architecture

\- Magisk root: effectively required, the non-root path is incomplete

\- about 400 MB free for the Java runtime, plus 60-100 MB per server

\- a stable connection while creating a server

\- a free playit.gg account to play from outside the home



\## Building



`compileSdk` and `targetSdk` 34, `minSdk` 24, Kotlin with `jvmTarget` 17. Open the project in

Android Studio and run the build: for the root path nothing else is needed.



Dependencies are OkHttp 4.12.0, Commons Compress 1.26.2, XZ for Java 1.9, Coil 2.6.0, AndroidX

core-ktx, appcompat, material, lifecycle-service, work-runtime, recyclerview and security-crypto.



The build also includes the tunnel agent: it downloads `playit-linux-aarch64` from the latest

release of \[playit-cloud/playit-agent](https://github.com/playit-cloud/playit-agent) and puts it

into the APK renamed as a native library, the only way to execute it inside an app's sandbox. An

internet connection is therefore needed at build time too.



The native binaries are not in the repository. To generate them you need WSL with `curl`, `ar`,

`tar` and `file`:



```

PROJECT\_PATH=/mnt/d/path/to/project ./tools/build\_jni\_libs.sh

```



The script downloads OpenJDK 17 and the compatibility libraries from the Termux repositories,

resolving the version from the directory index instead of using a fixed one, extracts the

packages, renames the ELF files and generates `jniLibs/` and `native\_binary\_map.json`. When it

finishes: Clean Project, Rebuild, then uninstall and reinstall the app, because native libraries

are extracted only on first install.



With `JDK\_SERIES=openjdk-21` the script prepares another JDK series. Note that each series adds

hundreds of megabytes to the APK, so the non-root path covers one Java version at a time.



\## Known limits



\*\*The tunnel lives as long as the agent does.\*\* Connections from outside go through the playit.gg

tunnel, which stays up only while the agent is running: after rebooting the phone it must be

restarted from the app. Tunnels are still created on the playit.gg website.



\*\*Non-root path incomplete.\*\* It fails on `libz.so.1` inside `jniLibs`, and the current map only

covers OpenJDK 17, so Minecraft versions that require Java 21 or 25 are out of reach on that path.



\*\*Quilt not supported.\*\* The installer used failed with `NoClassDefFoundError` on an internal

class. The code is commented out and kept.



\*\*Mods with external native libraries.\*\* Create Aeronautics and Sable require `libm.so.6`, which

does not exist in this environment. The log analyzer detects and explains the error, but does not

fix it.



\*\*Bedrock not supported.\*\* Official binaries do not exist for Android.



\*\*No backups.\*\* Worlds live in the app's private storage: uninstalling the app means losing them.



\*\*Version fixed per server.\*\* Game version and loader version cannot be changed on an existing

server.



\*\*One server at a time.\*\*



\## Roadmap



1\. creating and managing playit.gg tunnels directly from the phone, with port forwarding via DDNS

&#x20;  and mesh VPN as later alternatives

2\. completing the non-root path

3\. predefined quick commands in the fullscreen console

4\. on-demand installation of `libm.so.6` when the log analyzer detects the library

5\. changing game or loader version on an existing server

6\. world backup and export

7\. Quilt, and a feasibility check for Bedrock



\## Notes



With the root path the app remounts a partition with execute permission: it is a real system

modification, only do it knowing what it means.



The preventive termination of processes uses a pattern on the server paths and is deliberately

aggressive: it kills any residual Java process of the app.



CurseForge API keys stay on the device, in the Android keystore via

`androidx.security:security-crypto`, and are never written into the code or logs. Modrinth works

without a key. The playit.gg agent secret is kept the same way.



A phone is not designed to stay powered under continuous load: watch temperature and battery.



\## License



GPL-3.0-only, see `LICENSE`. Third-party attributions are in

\[THIRD\_PARTY\_NOTICES.md](THIRD\_PARTY\_NOTICES.md).

