# Live Note

Una sola nota, sempre nelle notifiche. Minimal, in stile Notion.

- **Live Update (Android 16+)**: la nota è una notifica promossa e persistente — chip nella barra di stato, schermata di blocco e Always-On Display. Sulle versioni precedenti resta una normale notifica fissa.
- **Schermo bloccato**: mostra solo il titolo. Tocca la notifica per aprire e modificare.
- **Spunte dalla notifica**: sotto la nota compaiono fino a 3 pulsanti con le prossime to-do da spuntare.
- **Nota lunga**: se non entra nella notifica, l'ultima riga dice quante righe mancano.
- **Finestra flottante**: l'app si apre come una scheda sopra lo sfondo, con la tastiera sotto. Tocca fuori per chiuderla.
- **Widget**: la nota sulla home, scrollabile, con gli angoli del tuo launcher. Toccalo per aprirla; la campanella in alto la mostra/nasconde dalle notifiche.
- **Timer**: l'orologio nella scheda nasconde la nota e la rimette nelle notifiche tra 30 min, 1 ora, 3 ore, stasera, domani o all'orario che scegli.
- **Persistente**: se la scorri via torna subito; si toglie solo dal pulsante in alto nell'app. Torna anche dopo il riavvio.
- **Markdown come Notion** nel corpo:

| Scrivi | Ottieni |
|---|---|
| `# ` `## ` `### ` | Titoli |
| `- ` | Elenco puntato |
| `1. ` | Elenco numerato |
| `[] ` | To-do (tocca il quadratino per spuntarlo) |
| `> ` | Toggle: tocca il triangolino per aprire/chiudere. Invio sul titolo del toggle crea una riga dentro; Invio su una riga vuota ne esce |
| `> ## ` o `## > ` | Toggle-titolo |
| `" ` | Citazione |
| `---` | Divisore |
| `**grassetto**` `*corsivo*` `` `codice` `` `~~barrato~~` | Stili in linea |

Invio continua l'elenco; Invio su una voce vuota la chiude.

## Download

Ultimo APK (beta 0.x): [Releases](../../releases/latest). Ogni push su `main` compila e pubblica una nuova release (GitHub Actions).

La chiave di firma in `app/signing.keystore` è solo per test: serve perché ogni build si installi sopra la precedente.
