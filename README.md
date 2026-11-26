# revReport 1.2.21

Aplicație Android pentru citirea datelor mașinii printr-un adaptor **ELM327 Bluetooth**: tablou de bord live, coduri de eroare, starea testelor de pregătire (readiness) și identificarea mașinii după VIN. Are suport specific pentru hibridele **Toyota Prius Gen 3 (XW30)**.

> Proiect personal, neafiliat cu Toyota sau cu vreun producător. Folosește-l pe propria răspundere și nu interacționa cu telefonul în timp ce conduci.

<!-- TODO: adaugă 2-3 capturi de ecran (Parameters, Faults, Info) -->

## Funcții

- **Tablou de bord live** (Parameters): viteză și turație mari, temperaturi (lichid de răcire, ulei, catalizator, aer admisie), niveluri ca bare (combustibil, baterie hibrid, sarcină, accelerație) și valori motor/electrice. Culorile se schimbă după praguri. Parametrii pe care mașina nu îi suportă nu apar.
- **Coduri de eroare** (Faults): citire stored / pending / permanent (Mode 03, 07, 0A), starea becului MIL, ștergere cu confirmare (refuzată dacă mașina se mișcă sau motorul merge). Peste 3000 de descrieri incluse.
- **Readiness monitors:** care teste le-a terminat mașina (catalizator, EVAP, sonda lambda etc.), pentru benzină și diesel.
- **Info vehicul:** citește VIN-ul din mașină, îl decodează online (NHTSA vPIC), identifică marca, modelul, generația și tipul de combustibil. Rezultatul se ține în cache, deci a doua oară merge și fără internet.
- **Detecție automată** hibrid / diesel din PID-urile mașinii.
- **Conectare automată** la ultimul adaptor folosit, la pornire și după pierderea conexiunii. Se oprește dacă apeși „Disconnect”.
- **Prius Gen 3 (opțional, PID-uri Toyota):** nivelul bateriei hibrid (SOC) și „efortul motorului” (puterea cerută motorului termic, ca % din 73 kW).

## Cerințe

| Ce | Detalii |
|---|---|
| Telefon | Android 7.0+ (minSdk 24) |
| Adaptor | **ELM327 Bluetooth clasic (SPP)**. **Nu** merg adaptoarele doar BLE sau Wi-Fi |
| Mașină | OBD2/EOBD (CAN recomandat) |
| Permisiuni | Bluetooth (Nearby devices) și internet (doar pentru decodarea VIN-ului) |

## Cum o folosești

1. Bagă adaptorul în mufa OBD2 (de obicei sub bord, pe partea șoferului) și pune contactul.
2. În setările telefonului asociază adaptorul (PIN-ul obișnuit: `1234` sau `0000`). Aplicația listează doar dispozitivele deja asociate.
3. În aplicație: **meniu → Bluetooth Device** și alege adaptorul.
4. Așteaptă „Searching for the car's protocol...”, apoi apar valorile live.
5. Pentru coduri de eroare sau VIN: meniul → **Faults** sau **Info**.

Dacă mașina nu răspunde (contact oprit), aplicația afișează „The car does not respond” și reîncearcă singură.

## Build

1. Deschide proiectul în **Android Studio**.
2. *File → Sync Project with Gradle Files*.
3. Conectează un telefon (cu USB debugging) și apasă **Run**.
4. Teste unitare: click dreapta pe `ReadinessTest` → *Run*.

## Structura codului

Toate fișierele sunt în `app/src/main/java/com/thrust/revreport/`.

| Fișier | Rol |
|---|---|
| `MainActivity.kt` | Ecranele (Compose), starea aplicației, polling, conectare automată |
| `revReportBluetoothManagement.kt` | Comunicarea cu ELM327: conectare, inițializare, PID-uri, DTC, VIN |
| `ObdParameters.kt` | Lista parametrilor afișați (PID, formulă, unitate) |
| `Readiness.kt` | Interpretarea testelor de pregătire (PID 0101) |
| `VehicleCatalog.kt` | Identificarea mărcii și generației din VIN |
| `VinDecoder.kt` | Decodare VIN online (NHTSA vPIC) |
| `DtcInfo.kt` / `DtcData.kt` | Descrieri coduri de eroare (`DtcData.kt` e generat) |
| `gen_dtc.py` | Generează `DtcData.kt` din `obd-trouble-codes.csv` |

### Cum adaugi un parametru

În `ObdParameters.kt` adaugi o linie în `obdParameters`:

```kotlin
ObdParameter("oilt", "OIL", "Engine oil temperature", "°C", "015C") { it.a()?.let { v -> v - 40.0 } },
```

Câmpurile sunt: cheie, etichetă scurtă, nume, unitate, PID și formula de decodare. Apoi îl treci într-un grup (`TEMP_KEYS`, `LEVEL_KEYS` etc.) din `MainActivity.kt`, ca să apară pe ecran.

PID-urile specifice producătorului se scriu `HEADER:COMANDĂ`, de exemplu `"7E2:2101"` (Toyota, ECU hibrid). Aplicația schimbă header-ul, citește răspunsul (inclusiv multi-cadru) și îl restabilește.

### Regenerarea descrierilor DTC

```
python3 gen_dtc.py obd-trouble-codes.csv DtcData.kt
```

## Limitări cunoscute

- **PID-urile Toyota** vin din liste făcute de comunitatea Prius (PriusChat), nu de la Toyota, și trebuie verificate pe mașină.
- **Codurile P1xxx / B1xxx / C1xxx** sunt specifice producătorului, iar descrierile din CSV nu se potrivesc neapărat cu marca ta.
- **Decodarea VIN** folosește baza de date NHTSA, orientată spre SUA. Pe mașini europene poate întoarce doar marca și anul.
- Ștergerea codurilor **resetează și starea testelor de pregătire** (readiness).
- Nu există suport BLE sau Wi-Fi.
- Testat pe: **TODO: completează mașina și telefonul**.

## Confidențialitate

VIN-ul citit din mașină este trimis către API-ul public NHTSA vPIC pentru decodare. Altfel, aplicația nu trimite date nicăieri.

## Testare fără mașină

Poți folosi [ELM327-emulator](https://github.com/Ircama/ELM327-emulator), de exemplu `python -m elm -s car -w` (scenariul unei hibride Toyota, cu server Bluetooth). Pe Windows serviciul SPP nu se publică în modul `-w`, deci urmează secțiunea despre Windows din README-ul emulatorului (port COM de intrare).

## Licență

TODO: alege o licență (de exemplu MIT) sau lasă proiectul privat.
