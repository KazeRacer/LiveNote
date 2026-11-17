# Live Note

Una sola nota, sempre nelle notifiche. Minimal, in stile Notion.

- **Live Update (Android 16+)**: la nota è una notifica promossa e persistente — chip nella barra di stato, schermata di blocco e Always-On Display. Sulle versioni precedenti resta una normale notifica fissa.
- **Schermo bloccato**: mostra solo il titolo. Tocca la notifica per aprire e modificare.
- **Persistente**: se la scorri via torna subito; si toglie solo dal pulsante in alto nell'app. Torna anche dopo il riavvio.
- **Markdown come Notion** nel corpo:

| Scrivi | Ottieni |
|---|---|
| `# ` `## ` `### ` | Titoli |
| `- ` | Elenco puntato |
| `1. ` | Elenco numerato |
| `[] ` | To-do (tocca il quadratino per spuntarlo) |
| `> ` | Toggle: tocca il triangolino per aprire/chiudere; le righe indentate sotto sono il contenuto |
| `" ` | Citazione |
| `---` | Divisore |
| `**grassetto**` `*corsivo*` `` `codice` `` `~~barrato~~` | Stili in linea |

Invio continua l'elenco; Invio su una voce vuota la chiude.

## Download

Ultimo APK: [Releases](../../releases/latest). Ogni push su `main` compila e pubblica una nuova release (GitHub Actions).

La chiave di firma in `app/signing.keystore` è solo per test: serve perché ogni build si installi sopra la precedente.
