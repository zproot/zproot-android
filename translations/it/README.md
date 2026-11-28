# zproot-android

Frontend Android per [zproot](https://github.com/zproot/zproot) — Linux su Android senza root o Termux.

Il tracer ptrace è scritto in Zig e si trova in [zproot](https://github.com/zproot/zproot). Questo repository contiene solo l'app Android.

## A cosa serve

- Installare ed eseguire distribuzioni Linux (Alpine, Debian, Ubuntu e altre...) su qualsiasi dispositivo Android
- Nessun root, nessun Termux, nessun server X11 separato
- Compatibile con sideload e F-Droid, non è necessario il Play Store
- Con licenza MIT

## Stato

Lavoro in corso. È presente l'implementazione M9/M10: l'installazione di Alpine viene eseguita e convalidata nello spazio di archiviazione privato dell'app e il loader PT_INTERP viene compilato per Android. La verifica end-to-end del programma di installazione aggiornato e dell'esecuzione dell'OS guest collegato dinamicamente su un dispositivo o un emulatore è ancora in sospeso. La riscrittura del percorso è stata precedentemente verificata end-to-end.

| Componente | Stato |
|-----------|--------|
| Struttura APK | completata |
| Servizio in primo piano | non avviato |
| Download ed estrazione di Alpine (M9) | implementati; verifica del dispositivo in sospeso |
| Shell BusyBox statica | presente nella base Alpine; temporanea fino all'integrazione di zyx |
| Binari collegati dinamicamente (M10) | implementati; verifica del dispositivo in sospeso |
| Interfaccia utente del terminale | non avviata |
| Registro multi-distribuzione | non avviato |
| Compositor Wayland | previsto |

Non aspettarti ancora un container Linux utilizzabile. Se hai bisogno di qualcosa di utilizzabile oggi, usa [pr](https://github.com/oonid/pr) o [Termux](https://github.com/termux/termux-app).

## Come funziona

L'app include un binario aarch64 nativo (`libzproot.so`) compilato da [zproot](https://github.com/zproot/zproot). Tale binario utilizza `ptrace()` di Linux per intercettare le chiamate di sistema e tradurre i percorsi del file system, creando un file system root virtuale senza privilegi di root.

Quando un programma guest richiama `openat("/etc/passwd")`, il tracer riscrive l'argomento della syscall in modo che punti a `/data/data/com.zproot/files/rootfs/etc/passwd`. Il kernel apre il file reale. Il guest vede `/etc/passwd`.

## Compilazione

Il binario del tracer non viene eseguito il commit. Viene scaricato da una release o compilato dal CI.

### Da GitHub Actions

Ogni push su questo repository attiva un flusso di lavoro che:

1. Clona `zproot/zproot`
2. Compila `zproot` e il loader PT_INTERP per `aarch64-linux-android`
3. Li impacchetta come `libzproot.so` e `libzproot-loader.so` in `app/src/main/jniLibs/arm64-v8a/`
4. Esegue i test unitari di Android e `./gradlew assembleRelease`
5. Carica l'APK come artefatto

Scarica l'APK dalla [scheda Azioni](https://github.com/zproot/zproot-android/actions).

### Localmente

Prerequisiti:

- ***JDK 17***
- ***Android SDK*** con build-tools e platform-tools
- ***Zig 0.16.0*** (solo se si compila il tracer autonomamente)

## Installazione

```bash
git clone https://github.com/zproot/zproot-android
cd zproot-android

# Opzione A: scarica un tracer precompilato
./scripts/fetch-binary.sh

# Opzione B: compilare il tracer dal codice sorgente
git clone https://github.com/zproot/zproot ../zproot
cd ../zproot && zig build -Dtarget=aarch64-linux-android -Doptimize=ReleaseSafe
cp zig-out/bin/zproot ../zproot-android/app/src/main/jniLibs/arm64-v8a/libzproot.so
cp zig-out/bin/zproot-loader ../zproot-android/app/src/main/jniLibs/arm64-v8a/libzproot-loader.so

# Compila l'APK
cd ../zproot-android
./gradlew assembleRelease
```

## L'APK si trova in:

```
app/build/outputs/apk/release/app-release.apk
```

## Installa con:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Struttura del progetto

```
zproot-android/
├── app/                       Modulo dell'applicazione Android
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/zproot/
│       │   ├── ZActivity.kt           Interfaccia utente Compose
│       │   └── RootfsManager.kt       Download, estrazione, symlink
│       └── jniLibs/arm64-v8a/
│           ├── libzproot.so           Tracciatore (non sottoposto a commit)
│           └── libzproot-loader.so    Loader PT_INTERP (non sottoposto a commit)
├── core/                      Modulo di libreria condivisa (previsto)
├── feature/                   Moduli delle funzionalità (previsti)
├── native/                    Staging della build nativa
├── rootfs/                    Ricette per ogni distribuzione
├── service/                   Servizio in primo piano (previsto)
├── compositor/                Compositor Wayland (previsto)
├── scripts/                   Script di compilazione e recupero
├── docs/                      Note su architettura e progettazione
├── test/                      Test unitari e strumentati
├── tools/                     Utility per sviluppatori
├── distribution/              Metadati F-Droid
├── third-party/               Dipendenze e licenze dei fornitori
├── translations/              strings.xml per ogni lingua
├── configs/                   Configurazioni di editor e linter
├── assets/                    Risorse di runtime
├── build-logic/               Plugin di convenzione Gradle
├── gradle/                    Wrapper e catalogo delle versioni
├── releases/                  Note di rilascio
├── patches/                   Patch delle dipendenze
├── dist/                      Staging dell'output della build
├── .github/                   Flussi di lavoro e modelli di ticket
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
└── README.md
```

## Note sulla compatibilità con Android

Android impone restrizioni ai processi delle app che un tracer `ptrace` deve aggirare:

- ***W^X (Write-XOR-Execute)***: impedisce l'esecuzione di file in directory scrivibili dall'app. Il tracer e il loader si trovano in `nativeLibraryDir` e sono denominati lib*.so.
- ***SELinux***: blocca determinate operazioni del file system. L'app non tocca nulla al di fuori della propria `filesDir`.
- ***Zygote seccomp***: blocca oltre 18 `syscall` tramite il filtro `BPF`. Il tracer intercetta `SIGSYS` e restituisce `-ENOSYS` in modo che i chiamanti tornino alle varianti di `syscall` precedenti.
- ***Phantom Process Killer (Android 12+)***: termina i processi figli generati tramite fork che consumano troppa CPU in background. Un servizio in primo piano con una notifica persistente è l'unica misura di mitigazione non root.

## Persistenza dei dati

Il rootfs si trova in `/data/data/com.zproot/files/rootfs/`. Sopravvive ai riavvii dell'app, ai riavvii del sistema e agli aggiornamenti. Viene eliminato solo quando l'utente disinstalla l'app o cancella i suoi dati.

L'installazione di una distribuzione estrae un file tar in quella directory. La rimozione di una distribuzione corrisponde a `rm -rf` nella sottodirectory. Nessun file system, nessun mount, nessun file immagine.

## Compilazione per altre ABI

> Attualmente è supportato solo arm64-v8a.

| ABI | Tracer | Loader | Stato |
|-----|--------|--------|--------|
| arm64-v8a | sì | sì | supportato |
| x86_64 | sì | parziale | in attesa di verifica M10 |
| armeabi-v7a | no | no | non avviato |
| x86 | no | no | non avviato |

I target a 32 bit necessitano di `struct` di registro, tabelle `syscall` e assembly `_start` separati. Consulta [docs/architecture/architecture.md](https://github.com/zproot/zproot-android/blob/main/docs/architecture/architecture.md) nel repository del tracer.

## Licenza

[MIT](https://github.com/zproot/zproot-android/blob/main/LICENSE)

## Nota

Il tracer è una reimplementazione "clean-room". Nessun codice sorgente di [proot](https://github.com/proot-me/proot), [termux-proot](https://github.com/termux/proot) o [proot-distro](https://github.com/termux/proot-distro) è stato letto, copiato o tradotto. Consulta [docs/clean-room.md](https://github.com/zproot/zproot/blob/master/docs/clean-room/clean-room.md) nel repository del tracer.

## Repository correlati

- [zproot/zproot](https://github.com/zproot/zproot) — core del tracer, scritto in Zig
- [zproot/.github](https://github.com/zproot/.github) — profilo dell'organizzazione
