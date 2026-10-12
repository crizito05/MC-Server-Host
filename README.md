# MC Server Host

App Android nativa che trasforma un telefono in un host per server **Minecraft Java Edition**:
Vanilla, Fabric, Forge e NeoForge, con catalogo mod da Modrinth e CurseForge, console in tempo
reale, editor di `server.properties`, log per sessione e analisi automatica degli errori.

Progetto indipendente, non affiliato a Mojang o Microsoft. Avviando un server l'utente scarica
il server ufficiale e accetta la [Minecraft EULA](https://www.minecraft.net/eula).

## Stato

| Loader | Stato |
|---|---|
| Vanilla | funzionante, testato |
| Fabric | funzionante, testato con mod reali e installazione automatica di Fabric API |
| Forge | funzionante, testato con più mod e più avvii |
| NeoForge | funzionante, testato con Create, Flywheel e Ponder |
| Quilt | non supportato, rimosso |

## Il problema tecnico

Un server Minecraft è un `.jar` Java, quindi il punto non è eseguire Java su Android: è farlo
dentro la sandbox di un'app normale. Da Android 10, se l'app dichiara `targetSdk >= 29`, lo
storage privato è montato `noexec` e nessun binario scaricato a runtime può essere eseguito.
Abbassare il `targetSdk` aggirerebbe il blocco ma impedirebbe la pubblicazione sul Play Store.

L'app usa due strade.

**Percorso root**, funzionante e testato. I pacchetti `.deb` di Termux vengono scaricati a
runtime, estratti in `files/usr`, la partizione viene rimontata con permesso di esecuzione e il
processo Java parte tramite `su`.

**Percorso non-root**, incompleto. Gli stessi binari vengono preparati in fase di build dentro
`jniLibs/arm64-v8a/`, perché Android estrae i nativi in una directory che è eseguibile. Ogni
file ELF deve essere rinominato `lib*.so`, unico pattern che Android estrae, e la corrispondenza
tra nome originale e nome rinominato sta in `assets/native_binary_map.json`.

Il resto dell'infrastruttura:

- estrazione dei `.deb` in puro Kotlin con `commons-compress` e `xz`, perché su Android non
  esistono né `ar` né `tar`
- versione del JDK scelta dal manifest ufficiale Mojang, non hardcoded: per ogni versione di
  Minecraft viene usato il major version corretto
- librerie di compatibilità scaricate singolarmente dai repository Termux e copiate nella
  cartella `lib` del JDK: `libz.so.1`, `libzip.so`, `libandroid-shmem.so`, `libandroid-spawn.so`
- per i loader moddati il server vanilla viene scaricato dal codice dell'app prima di lanciare
  l'installer, invece di affidarsi al flag `-downloadMinecraft`: quel download avverrebbe dentro
  un processo Java avviato sotto `su`, ed è il punto più fragile della catena
- `unix_args.txt` per Forge e NeoForge, `fabric-server-launch.jar` per Fabric, con esclusione
  esplicita di `win_args.txt`, che altrimenti verrebbe scambiato per quello giusto

## Funzionalità

- creazione server con scelta di loader, versione di Minecraft, versione del loader e RAM,
  validata contro la memoria reale del dispositivo
- pulsante di ottimizzazione della RAM prima dell'avvio
- catalogo mod da Modrinth e CurseForge, con icone, ricerca, dettaglio e scelta della versione
- installazione in foreground service con notifica che riporta la mod e la percentuale reale
- download scritto su file temporaneo `.part` e rinominato solo a trasferimento completo e
  verificato sul `Content-Length`
- dipendenze richieste installate automaticamente, e Fabric API installata alla creazione di un
  server Fabric
- verifica degli aggiornamenti per singola mod o in blocco, con blocco della versione: una mod
  bloccata non viene aggiornata finché non la sblocchi
- console in tempo reale con scorrimento automatico e comandi inviabili al server
- editor di `server.properties` con campi strutturati e modalità testo libero
- log per sessione in `app_logs/` e log dei fallimenti di creazione in `creation_logs/`
- analizzatore dei log che traduce gli errori in messaggi leggibili: mod incompatibili, libreria
  nativa mancante, whitelist, `session.lock`, problemi di rete
- eliminazione completa del server con fallback root, e terminazione preventiva dei processi
  Java residui prima di ogni avvio

## Requisiti

- Android 7.0 o superiore, `minSdk 24`, architettura `arm64-v8a`
- root con Magisk: di fatto obbligatorio, il percorso non-root è incompleto
- circa 400 MB liberi per il runtime Java, più 60-100 MB per ogni server
- connessione stabile in fase di creazione del server

## Compilazione

`compileSdk` e `targetSdk` 34, `minSdk` 24, Kotlin con `jvmTarget` 17. Apri il progetto in
Android Studio ed esegui la build: per il percorso root non serve altro.

Le dipendenze sono OkHttp 4.12.0, Commons Compress 1.26.2, XZ for Java 1.9, Coil 2.6.0,
AndroidX core-ktx, appcompat, material, lifecycle-service, work-runtime, recyclerview e
security-crypto.

I binari nativi non sono nel repository. Per generarli serve WSL con `curl`, `ar`, `tar` e
`file`:

```
PROJECT_PATH=/mnt/d/percorso/del/progetto ./tools/build_jni_libs.sh
```

Lo script scarica OpenJDK 17 e le librerie di compatibilità dai repository Termux, risolvendo
la versione dall'indice della cartella invece di usarne una fissa, estrae i pacchetti, rinomina
i file ELF e genera `jniLibs/` e `native_binary_map.json`. Al termine: Clean Project, Rebuild,
e disinstalla e reinstalla l'app, perché i nativi vengono estratti solo alla prima installazione.

Con `JDK_SERIES=openjdk-21` lo script prepara un'altra serie di JDK. Tieni presente che ogni
serie aggiunge centinaia di megabyte all'APK, quindi il percorso non-root copre una sola
versione di Java alla volta.

## Limiti noti

**Si gioca solo in LAN.** Il modulo di rete per le connessioni dall'esterno non è stato scritto:
l'indirizzo va letto dalle impostazioni Wi-Fi del telefono.

**Percorso non-root incompleto.** Si blocca su `libz.so.1` dentro `jniLibs`, e la mappa attuale
copre solo OpenJDK 17, quindi su quel percorso restano fuori le versioni di Minecraft che
richiedono Java 21 o 25.

**Quilt non supportato.** L'installer usato falliva con `NoClassDefFoundError` su una classe
interna. Il codice è commentato e conservato.

**Mod con librerie native esterne.** Create Aeronautics e Sable richiedono `libm.so.6`, che in
questo ambiente non c'è. L'analizzatore dei log rileva l'errore e lo spiega, ma non lo risolve.

**Bedrock non supportato.** I binari ufficiali non esistono per Android.

**Nessun backup.** I mondi vivono nello storage privato dell'app: disinstallarla significa
perderli.

**Versione fissa per server.** Non si può cambiare versione di gioco o di loader su un server
già creato.

**Un solo server alla volta.**

## Roadmap

1. modulo di rete: tunnel, poi port forwarding con DDNS, poi VPN mesh, con fallback a cascata
2. completamento del percorso non-root
3. comandi rapidi predefiniti in console a schermo intero
4. installazione di `libm.so.6` su richiesta quando l'analizzatore dei log rileva la libreria
5. cambio di versione di gioco o loader su un server esistente
6. backup ed export dei mondi
7. Quilt, e verifica di fattibilità per Bedrock

## Note

Con il percorso root l'app rimonta una partizione con permesso di esecuzione: è una modifica
reale al sistema, da fare solo sapendo cosa comporta.

La terminazione preventiva dei processi usa un pattern sui percorsi dei server, ed è volutamente
aggressiva: termina qualunque processo Java residuo dell'app.

Le chiavi API di CurseForge restano sul dispositivo, nel keystore Android tramite
`androidx.security:security-crypto`, e non vengono scritte nel codice né nei log. Modrinth
funziona senza chiave.

Un telefono non è progettato per restare acceso sotto carico continuo: sorveglia temperatura e
batteria.

## Licenza

GPL-3.0-only, vedere `LICENSE`. Le attribuzioni di terze parti sono in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).