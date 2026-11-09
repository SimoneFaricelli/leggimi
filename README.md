# Leggimi

App Android che legge ad alta voce i tuoi documenti, come un audiolibro, con le voci di [ElevenLabs](https://elevenlabs.io).

- **Formati**: PDF, Word (DOCX), ODT, EPUB, RTF, HTML, Markdown, TXT.
- **ElevenLabs**: scegli voce e modello dall'app, per tutti i documenti o per uno solo. Puoi regolare stabilità, somiglianza, stile e velocità della voce.
- **Lettura**: il testo scorre con il brano in lettura evidenziato. Tocca un paragrafo per saltare lì; l'indice dei capitoli si apre dal lettore.
- **Riproduzione**: continua in background, con i controlli in notifica, sulla schermata di blocco e dalle cuffie. Hai velocità da 0,75× a 2× e un timer di spegnimento (dopo N minuti o a fine capitolo).
- **Risparmio crediti**: l'audio generato resta salvato sul telefono, quindi riascoltare non costa nulla. La cache si svuota dalle impostazioni.
- **Posizione**: ogni documento riprende da dove l'avevi lasciato.

## Primo utilizzo
1. Installa `dist/Leggimi-debug.apk` sul telefono. Android chiederà di consentire l'installazione da origini sconosciute.
2. Apri **Impostazioni** e incolla la chiave API di ElevenLabs. La trovi in *elevenlabs.io → Settings → API Keys*.
   - La chiave deve avere almeno i permessi *Text to Speech*, *Voices (read)*, *Models (read)* e *User (read)*.
3. Premi **Salva e verifica**: compaiono il piano e i caratteri residui.
4. Scegli una **voce** e un **modello**:
   - *Eleven Multilingual v2*: ottima resa in italiano per testi lunghi.
   - *Flash v2.5*: più rapido ed economico.
5. Torna alla libreria, premi **Aggiungi** e scegli un documento. Puoi anche usare "Apri con" o "Condividi" da un'altra app.

## Come funziona
Nei PDF i paragrafi vengono ricostruiti dalla posizione delle righe sulla pagina. Due righe restano unite, tranne quando c'è un segnale chiaro di nuovo paragrafo:
- inizia un punto elenco;
- lo spazio verticale tra le righe è ampio;
- cambia la dimensione del carattere;
- una frase finisce su una riga corta, oppure è seguita da un rientro.

I paragrafi che proseguono alla pagina successiva restano uniti. Intestazioni, piè di pagina e numeri di pagina ripetuti non vengono letti. Quando le regole di estrazione cambiano, i documenti già importati vengono rielaborati alla prima apertura.

Ogni paragrafo diventa una richiesta a ElevenLabs (`/v1/text-to-speech`). I paragrafi più lunghi di 1.200 caratteri vengono divisi sulle frasi. Così, toccando un paragrafo, la lettura parte esattamente da lì.

Ogni richiesta porta come contesto fino a 500 caratteri del testo precedente e successivo, per mantenere un'intonazione continua tra un paragrafo e l'altro. Mentre ascolti, l'app genera in anticipo almeno i due paragrafi successivi e almeno 1.500 caratteri di testo.

Gli mp3 vengono salvati in `files/audio/<documento>/`. Il nome del file dipende da testo, voce, modello e regolazioni: se cambi voce, l'audio si rigenera, e se torni alla voce di prima riusa quello già pagato.

La chiave API è cifrata con una chiave AES dell'Android Keystore e non viene inclusa nei backup.

## Compilare
Serve solo Docker: tutta la toolchain Android (JDK 21, SDK 37, Gradle 9.9) è nell'immagine `docker/Dockerfile`.

```bash
build/build.sh             # unit test + APK debug   -> dist/Leggimi-debug.apk
build/build.sh release     # unit test + APK firmato -> dist/Leggimi-<versione>.apk
build/build.sh -- lintDebug   # qualsiasi task Gradle
```

La prima build `release` crea un keystore in `~/.leggimi-keystore/`. **Fanne un backup**: senza quel keystore non potrai installare aggiornamenti sopra l'app esistente.

In `samples/` ci sono tre documenti di prova (DOCX, PDF, Markdown).

## Struttura
```
app/src/main/java/it/faricelli/leggimi/
  data/extract/   estrattori di testo, uno per formato
  data/text/      modello del documento e Segmenter (blocchi per la sintesi)
  data/eleven/    client REST di ElevenLabs
  data/audio/     cache degli mp3
  data/db/        Room: libreria e posizione di lettura
  data/settings/  DataStore + chiave API cifrata
  player/         PlayerEngine (coda, prefetch, timer) e PlaybackService (Media3)
  ui/             schermate Compose: libreria, lettore, impostazioni, scelta voce/modello
```
