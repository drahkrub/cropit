# PNG → Text mit Ollama

Eigenständiges Kommandozeilenprojekt mit **Spring AI 2.0.2**, Spring Boot 4.1.2
und Java 17+. Maven 3.6.3+ ist zum Bauen erforderlich. Cropit muss dafür nicht laufen.

Das Programm sendet jedes PNG zusammen mit einem Prompt an ein visuelles Modell
auf einem konfigurierten Ollama-Server. Die Antwort wird unverändert als UTF-8-Datei
gespeichert: `page-001.png` → `page-001.md`. Die Modellantwort bestimmt das
tatsächliche Textformat; die Dateiendung konvertiert den Inhalt nicht.

## Bauen und konfigurieren

```bash
cd png-to-text
mvn package
cp converter-example.properties converter.properties
```

In `converter.properties` die Ollama-URL, den **genauen Modellnamen**, Quell- und
Zielverzeichnis eintragen. Das Modell muss bereits auf dem Ollama-Server installiert
sein und Bilder unterstützen. Es werden keine Modelle automatisch heruntergeladen.
Die URL bezeichnet die Ollama-Basis, z.B. `http://192.168.1.50:11434`, ohne `/api/chat`
oder `/v1`. Der Rechner, auf dem dieses Programm läuft, muss den Server erreichen können.

```bash
java -jar target/png-to-text-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:./converter.properties
```

Alternativ komplett über Umgebungsvariablen:

```bash
OLLAMA_BASE_URL=http://192.168.1.50:11434 \
OLLAMA_MODEL='mein-vision-modell:latest' \
INPUT_DIR=/daten/pngs \
OUTPUT_DIR=/daten/markdown \
java -jar target/png-to-text-0.0.1-SNAPSHOT.jar
```

Ein Aufruf startet genau einen Durchlauf und beendet sich danach; es gibt keinen
Webserver oder Verzeichniswächter. Relative Pfade beziehen sich auf das aktuelle
Arbeitsverzeichnis. Die Verzeichnisse liegen auf dem Rechner des Programms, nicht
auf dem Ollama-Server. Als Quelle kann direkt ein Cropit-Jobverzeichnis `output/`
verwendet werden.

## Optionen

Alle Optionen können auch als `--png-text.<name>=<wert>` übergeben werden.
Kommandozeilenargumente überschreiben Einträge aus Konfigurationsdateien.

| Umgebungsvariable | Eigenschaft unter `png-text.` | Standard |
|---|---|---|
| `OLLAMA_BASE_URL` | `ollama-url` | `http://localhost:11434` |
| `OLLAMA_MODEL` | `model` | erforderlich |
| `INPUT_DIR` | `input-dir` | erforderlich |
| `OUTPUT_DIR` | `output-dir` | erforderlich; wird bei Bedarf angelegt |
| `CONVERSION_PROMPT` | `prompt` | vollständige Transkription als Markdown ohne Zusammenfassung |
| `PROMPT_FILE` | `prompt-file` | optional; UTF-8-Datei, hat Vorrang vor `prompt` |
| `OUTPUT_EXTENSION` | `extension` | `md`; ohne führenden Punkt, z.B. `txt` oder `json` |
| `OVERWRITE` | `overwrite` | `false` |
| `CONNECT_TIMEOUT` | `connect-timeout` | `10s` |
| `READ_TIMEOUT` | `read-timeout` | `10m` pro HTTP-Aufruf |
| `CONTEXT_SIZE` | `context-size` | `16384` Tokens; an Modell und verfügbaren Speicher anpassen |
| `MAX_TOKENS` | `max-tokens` | `8192` maximale Ausgabetokens |

Beispiel für reinen Text mit eigenem Prompt:

```bash
java -jar target/png-to-text-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:./converter.properties \
  --png-text.prompt-file=./prompt.txt \
  --png-text.extension=txt
```

## Verhalten bei mehreren Dateien und Fehlern

- Verarbeitet alle regulären `.png`-Dateien direkt im Quellverzeichnis, auch `.PNG`,
  alphabetisch sortiert und nacheinander. Unterordner und symbolische Links werden
  nicht eingelesen. Pro Bild wird eine neue Anfrage ohne bisherigen Chatverlauf erzeugt.
- Der Ollama-Denkmodus wird für die Transkription ausgeschaltet (`think=false`).
- Gleiche Quell- und Zielverzeichnisse sowie mehrdeutige Ausgabenamen werden abgewiesen.
- Bestehende Ausgabedateien werden standardmäßig übersprungen. Ein erneuter Aufruf
  setzt damit einen abgebrochenen Lauf fort. Mit `OVERWRITE=true` werden sie ersetzt.
- Antworten werden zunächst in eine temporäre Datei im Zielverzeichnis geschrieben
  und erst nach vollständigem Schreiben auf den endgültigen Namen verschoben.
- Bei einer fehlgeschlagenen Anfrage wird die Datei protokolliert und mit der nächsten
  fortgefahren. Leere Antworten und vom Modell als `length` markierte, abgeschnittene
  Antworten gelten als Fehler. Bestehende Ausgaben bleiben bei solchen Fehlern erhalten.
  Spring AI kann vorübergehende HTTP-Fehler intern erneut versuchen.
- Am Ende werden geschriebene, übersprungene und fehlgeschlagene Dateien gezählt.
  Exit-Code `0`: alle erfolgreich oder übersprungen. Exit-Code `1`: mindestens ein
  Fehler oder ungültige Konfiguration. Ein Verzeichnis ohne PNGs gilt als Fehler.
- Die Qualität und Vollständigkeit der Transkription hängen vom Modell ab. Das Programm
  validiert weder Markdown-/JSON-Syntax noch den erkannten Inhalt.

## Tests und Quellen

```bash
mvn test
```

Die Tests prüfen die echte Spring-AI/Ollama-HTTP-Anbindung gegen einen lokalen
Testserver sowie Dateizuordnung, Fortsetzung, Fehler und Konfiguration.
Ein echter Ollama-Server ist für diese Tests nicht erforderlich.

- [Spring AI 2.0.2 Release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.2)
- [Spring-AI-/Spring-Boot-Kompatibilität](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Ollama-Anbindung und Multimodalität](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)
