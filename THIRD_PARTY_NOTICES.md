\# Attribuzioni di terze parti



Il repository non contiene binari di terze parti: i file nativi si generano in locale con

`tools/build\_jni\_libs.sh`, scaricandoli dalle fonti ufficiali. L'APK compilato invece li

contiene, e il percorso root li scarica a runtime.



Licenze verificate sui `build.sh` del progetto

\[termux-packages](https://github.com/termux/termux-packages) e sulle fonti originali.



\## Runtime Java e pacchetti Termux



Scaricati da <https://packages.termux.dev>.



| Pacchetto | Versione verificata | Uso | Licenza |

|---|---|---|---|

| `openjdk-17` | 17.0.20 | runtime Java | GPL-2.0 con Classpath Exception |

| `openjdk-21` | 21.0.12 | runtime Java | GPL-2.0 con Classpath Exception |

| `openjdk-25` | 25.0.4 | runtime Java | GPL-2.0 con Classpath Exception |

| `zlib` | 1.3.2 | `libz.so.1`, richiesto da `libzip.so` della JVM | ZLIB |

| `libandroid-shmem` | 0.7 | emulazione della memoria condivisa System V, richiesta da `libjvm.so` | BSD 3-Clause |

| `libandroid-spawn` | 0.3 | `posix\_spawn` | BSD 2-Clause |



L'indice dei pacchetti di Termux non dichiara il campo licenza: i valori qui sopra sono quelli

dichiarati dai `build.sh` ufficiali. Per OpenJDK la Classpath Exception non compare nel campo

licenza ma è presente nelle fonti, ed è quella che consente di eseguire codice di terze parti,

qui il server e le mod, senza che ricada nella licenza del JDK.



Le versioni cambiano: lo script di generazione risolve quella disponibile dall'indice della

cartella invece di usarne una fissa.



\## Librerie dell'app



| Libreria | Versione | Uso | Licenza |

|---|---|---|---|

| OkHttp | 4.12.0 | download e chiamate API | Apache-2.0 |

| Apache Commons Compress | 1.26.2 | estrazione dei `.deb` | Apache-2.0 |

| XZ for Java | 1.9 | decompressione `data.tar.xz` | dominio pubblico |

| Coil | 2.6.0 | icone delle mod | Apache-2.0 |

| AndroidX core-ktx | 1.13.1 | | Apache-2.0 |

| AndroidX appcompat | 1.7.0 | | Apache-2.0 |

| Material Components | 1.12.0 | temi e widget | Apache-2.0 |

| AndroidX lifecycle-service | 2.8.4 | servizio in foreground | Apache-2.0 |

| AndroidX work-runtime | 2.9.1 | | Apache-2.0 |

| AndroidX recyclerview | 1.3.2 | liste | Apache-2.0 |

| AndroidX security-crypto | 1.1.0-alpha06 | custodia delle chiavi API | Apache-2.0 |



\## API e servizi esterni



| Servizio | Uso | Condizioni |

|---|---|---|

| \[Modrinth API](https://docs.modrinth.com) | catalogo mod, versioni, icone | User-Agent identificativo, chiave opzionale |

| \[CurseForge Core API](https://docs.curseforge.com) | catalogo mod alternativo | chiave obbligatoria, fornita dall'utente |

| `launchermeta.mojang.com`, `piston-meta.mojang.com` | manifest delle versioni e del JDK richiesto | termini Mojang |

| `maven.minecraftforge.net`, `maven.neoforged.net`, `meta.fabricmc.net` | versioni dei loader | termini dei rispettivi progetti |



Su CurseForge l'autore di una mod può disattivare la distribuzione verso applicazioni esterne:

in quel caso l'API non restituisce l'indirizzo di download e l'app non può installare né

aggiornare quella mod.



\## Software eseguito dall'app, non distribuito



Scaricato e avviato su richiesta esplicita dell'utente.



\- server Minecraft vanilla: proprietario Mojang e Microsoft, soggetto alla

&#x20; \[Minecraft EULA](https://www.minecraft.net/eula) che l'utente accetta tramite `eula.txt`

\- Forge e NeoForge: LGPL-2.1

\- Fabric Loader e Fabric API: Apache-2.0

\- le singole mod: ciascuna con la propria licenza



Questo progetto non ridistribuisce nessuno di questi.

