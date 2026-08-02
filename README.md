# Cleo BLE Bridge

App Android che trasforma un tablet in un ponte tra una cyclette **non smart** (Diadora Cleo) e **MyWhoosh**, permettendo di allenarsi con watt stimati anche senza un vero sensore di potenza.

## Come funziona

Il tablet legge la velocità mostrata dalla Cleo (a mano, o via OCR da fotocamera/webcam) e la trasmette via **Bluetooth LE** come un sensore standard **Cycling Speed and Cadence (CSC)**. MyWhoosh, in modalità "Speed Sensor", riceve il tablet come un normale sensore di velocità Bluetooth e calcola da solo i watt stimati.

```
CLEO (display velocità)
      ↓
TABLET ANDROID (legge la velocità)
      ↓ Bluetooth LE (CSC Speed Sensor)
PC WINDOWS → MyWhoosh → Watt stimati
```

## Le tre modalità

All'avvio, l'app chiede quale modalità usare:

1. **TEST** — inserisci la velocità a mano (con pulsanti +/- da 0.5 km/h), utile per verificare che la trasmissione BLE funzioni prima di collegare qualunque fotocamera.
2. **FOTOCAMERA** — usa la fotocamera integrata del tablet (anteriore o posteriore, con pulsante di cambio). Si disegna col dito un riquadro sul display della Cleo, e l'OCR legge il numero da lì.
3. **WEBCAM USB** — stessa logica OCR, ma la sorgente video è una webcam USB esterna (es. Logitech) collegata tramite adattatore USB-C, utile per un posizionamento più comodo della fotocamera. Usa la libreria [UVCAndroid](https://github.com/shiyinghan/UVCAndroid).

In entrambe le modalità con fotocamera, il numero letto passa attraverso:
- una correzione automatica se il punto decimale non viene riconosciuto (es. "200" → "20.0"),
- un filtro anti-rumore che tiene l'ultimo valore buono invece di seguire letture singole sbagliate.

## Dettagli tecnici importanti

- **Circonferenza ruota**: impostata in `CscPeripheral.kt` (`WHEEL_CIRCUMFERENCE_MM`), deve restare coerente con quanto MyWhoosh assume per calcolare la velocità dai giri ruota.
- **Fattore di correzione velocità di gioco**: `GAME_SPEED_CORRECTION_FACTOR` in `CscPeripheral.kt` — MyWhoosh traduce potenza in velocità in-game secondo una propria curva; questo fattore compensa la differenza tra velocità reale pedalata e velocità mostrata in gioco in pianura. Va tarato empiricamente.
- **Aggiornamento BLE**: ogni 250ms, per ridurre gli scatti di quantizzazione nei giri ruota trasmessi.

## Come compilare

Il progetto include un workflow **GitHub Actions** (`.github/workflows/build.yml`) che compila l'APK automaticamente a ogni push, senza bisogno di Android Studio in locale:

1. Carica il progetto in un repository GitHub
2. Vai sulla scheda "Actions", aspetta il completamento (pallino verde)
3. Scarica l'APK generato dalla sezione "Artifacts"
4. Installalo sul tablet (serve abilitare "Origini sconosciute")

## Struttura del progetto

```
app/src/main/java/com/cleo/blebridge/
├── MainActivity.kt          → schermata di scelta modalità
├── TestModeActivity.kt      → modalità test (velocità manuale)
├── CameraModeActivity.kt    → modalità fotocamera integrata
├── UsbCameraModeActivity.kt → host della modalità webcam USB
├── UsbCameraFragment.kt     → logica webcam USB (UVCAndroid + OCR)
├── CscPeripheral.kt         → il "cuore" BLE: servizio CSC Speed Sensor
└── RoiOverlayView.kt        → overlay per selezionare la zona del display da leggere
```

## Licenza

Vedi il file `LICENSE` — tutti i diritti riservati, uso personale.
