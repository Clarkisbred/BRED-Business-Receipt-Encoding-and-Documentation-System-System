# AI Receipt Importer Development Setup

This prepares the Java dependencies and optional local OCR/AI tools for future importer work. It does not implement
receipt extraction, call an AI provider, or upload attachments. BRED's existing features remain usable if Ollama or
Tesseract is unavailable.

## 1. Required software

- Windows 11 (for the Windows OCR and MSI steps).
- Temurin/OpenJDK 21 LTS. The Maven compiler target remains Java 21.
- Apache Maven 3.10.2 or compatible Maven 3.x.
- A Java 21 JDK when building Windows packages; `jpackage` bundles a Java runtime in BRED's app image.
- Ollama and Tesseract are optional external programs; neither is bundled in BRED's installer.

## 2. Maven dependencies

| Library | Version | Purpose / status |
| --- | --- | --- |
| Apache POI (`poi`) | 5.4.1 | Core workbook and cell APIs, now declared directly. |
| Apache POI OOXML (`poi-ooxml`) | 5.4.1 | Existing XLSX support; XLS and XLSX workbook handling remains available. |
| Jackson Databind | 2.17.2 | Already present for JSON. |
| Jackson JSR-310 | 2.17.2 | Already present for Java date/time types. |
| OpenCSV | 5.12.0 | CSV parsing with quoted fields and configurable formats. |
| Apache PDFBox | 3.0.8 | PDF text and page processing. Maven resolves `fontbox` and `pdfbox-io`; do not download standalone/app/debugger/preflight packages. |
| Tess4J | 5.21.0 (`provided`) | Compile-time Java/JNA bridge. Its published Maven metadata targets Java 17, compatible with Java 21. |

JUnit Jupiter 5.10.3 and FlatLaf 3.5.4 remain unchanged. Tess4J also declares PDFBox 3.0.8, matching the direct PDFBox dependency. Its PDFBox tools dependency is excluded so
the application does not pull in PDFBox's debugger application. Scanned PDF pages can be rendered with PDFBox's core
`PDFRenderer` and passed to OCR as images. Java's built-in `java.net.http.HttpClient` remains the HTTP client; no
additional HTTP client dependency is used.

Tess4J 5.21.0's Maven artifact contains its own Tesseract 5.5.4 DLL and English/OSD trained data, which may not match a
separately installed Tesseract version. To honor the requirement not to bundle OCR engines or model data, Tess4J is
`provided`: Maven resolves it and makes it available for compile-time work, but the shaded BRED JAR/MSI excludes it
and its OCR payload. This setup does not yet invoke Tess4J at runtime. The build preflight rejects trained-data and
Tesseract/Leptonica DLL payloads in the application JAR.

The runtime Maven dependencies are included in the existing shaded application JAR. The build preflight checks that
the POI, CSV, and PDFBox classes are present before packaging.

## 3. Ollama (optional local AI)

Official Windows installer: <https://ollama.com/download/windows>

Install only if Ollama is not already present:

```powershell
winget install Ollama.Ollama
ollama --version
ollama pull qwen2.5:3b
ollama list
Invoke-RestMethod http://localhost:11434/api/tags
```

`qwen2.5:3b` is a configurable starting model, not an installer requirement. It can run without a dedicated GPU, though
performance depends on available memory and CPU. The model is downloaded and stored by Ollama outside BRED.

The AI provider layer supports `RMS_AI_PROVIDER` values `ollama` (default), `cloud`, `hybrid`, and `disabled`.
`RMS_OLLAMA_BASE_URL` defaults to `http://localhost:11434`, and `RMS_OLLAMA_MODEL` defaults to `qwen2.5:3b`.
Hybrid mode tries Ollama first and falls back to cloud only if local generation fails.

Cloud mode uses an OpenAI-compatible chat-completions endpoint. Configure its HTTPS base URL with
`RMS_CLOUD_AI_BASE_URL`, model with `RMS_CLOUD_AI_MODEL` (defaults to `gpt-4o-mini`), and optional API key with
`RMS_CLOUD_AI_API_KEY`. Cloud provider construction does not require credentials; an actual request reports a clear
configuration error if the endpoint or key is missing. Requests time out after 60 seconds by default;
`RMS_AI_REQUEST_TIMEOUT_SECONDS` changes this value.

Provider requests are text-only; the Phase 2 API does not transmit attachment data. When a request is derived from an
imported document, cloud AI rejects it unless an administrator explicitly sets
`RMS_CLOUD_AI_ATTACHMENTS_CONSENT=true`. In hybrid mode, an unavailable Ollama provider falls back to cloud only
under that same consent setting.

## 4. Receipt document import and review

Administrators can import `.xlsx`, `.xls`, `.csv`, `.pdf`, `.png`, `.jpg`, `.jpeg`, `.bmp`, `.tif`, and `.tiff` files
from the Receipts screen. The importer checks extension, file signature, and readability; each file is limited to
20 MiB, and a session is limited to 50 files / 100 MiB total. Files are staged in a private temporary directory and
removed after processing. Extracted data remains in the review session only: this phase does not create receipts,
upload attachments, assign company ownership, or save to the database.
To avoid oversized prompts, AI extraction is limited to 100,000 extracted characters per document; longer text is
preserved for review but is not sent to an AI provider.

Excel and CSV rows retain headings and row references; PDF pages retain page references. Scanned PDF pages are rendered
at 200 DPI and images are passed to Tesseract. The selected AI provider receives only extracted text, never the
original document bytes. The AI response is parsed as a list of receipt drafts; discrepancies and low-confidence
values remain visible for administrator review.

## 5. Tesseract OCR (optional external runtime)

Official Windows installer/project: <https://github.com/UB-Mannheim/tesseract/wiki>

Install with winget if Tesseract is missing:

```powershell
winget install UB-Mannheim.TesseractOCR
tesseract --version
(Get-Command tesseract.exe).Source
```

Keep Tesseract and its English language data outside the BRED application package. The importer invokes the installed
executable with `ProcessBuilder` (English language, `RMS_AI_REQUEST_TIMEOUT_SECONDS` per-operation timeout); it does
not load Tess4J or bundle Tesseract.
If the executable is not on `PATH`, set `RMS_TESSERACT_EXE` in the process environment to the executable's actual path
on that computer. Do not put a developer-specific absolute path in source or shared configuration. The default is
`tesseract.exe` from `PATH`.

Tess4J's Windows native integration may require the Microsoft Visual C++ v14 Redistributable. Install the supported
x64 runtime separately if the OCR runtime reports a missing Visual C++ DLL:
<https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist>.

## 6. PDFBox 3 configuration

Use the PDFBox 3 API. For example, load documents with `org.apache.pdfbox.Loader.loadPDF(...)` and extract text with
`org.apache.pdfbox.text.PDFTextStripper`. PDFBox is a Java dependency and needs no separate PDFBox installer.
Scanned/image-only pages require OCR; PDFBox text extraction alone cannot recognize their image content. Tess4J's
native runtime needs a deliberate version-matched deployment strategy before runtime integration; do not make BRED
depend on the Tess4J-bundled DLLs or model data under this setup.

## 6. Cloud AI configuration and privacy

No cloud provider or paid subscription is selected. The prepared configuration accepts:

- `RMS_AI_PROVIDER=cloud`
- `RMS_CLOUD_AI_BASE_URL=https://api.example.com`
- `RMS_CLOUD_AI_API_KEY=<provider-issued-secret>`

Set the key only in the environment of the process that launches BRED or inject it from an approved OS/CI secret
manager. Do not add a real key to `application.properties`, source control, logs, screenshots, or support reports.
`RMS_CLOUD_AI_BASE_URL` must use HTTPS. No credentials are generated by this project.

Configuration does not enable any uploads. The importer is not implemented; when cloud processing is added, private
receipt attachments must not be sent until an administrator explicitly enables and consents to that behavior.

## 7. Verification commands

Run from the `receipt-management-system` project directory:

```powershell
mvn dependency:resolve
mvn clean compile
mvn test
mvn dependency:tree -Dincludes=org.apache.poi:poi,org.apache.poi:poi-ooxml,com.opencsv:opencsv,org.apache.pdfbox:pdfbox,org.apache.pdfbox:fontbox,org.apache.pdfbox:pdfbox-io,net.sourceforge.tess4j:tess4j
```

Also verify optional external tools as needed:

```powershell
java -version
mvn -version
ollama --version
ollama list
Invoke-RestMethod http://localhost:11434/api/tags
tesseract --version
```

The application can still be built and used without Ollama or Tesseract installed. The importer cannot perform local
AI/OCR work until its respective external tool is installed and the feature is implemented.

## 8. Windows packaging

The existing Windows build shades runtime Maven dependencies into the application JAR, creates a `jpackage` app
image, then creates an MSI. The app image includes its Java runtime, so end users do not need Java or Maven installed.
PDFBox, OpenCSV, POI, and Jackson are included through Maven; the script checks their classes in the shaded JAR.
Tess4J is `provided` and its native OCR engines/trained data are explicitly rejected from the package.

Ollama, its model files, Tesseract, and Tesseract language data are separate user-managed installations and are not
copied into the MSI. OCR requires a working Tesseract installation and, where needed, the Microsoft Visual C++ v14
Redistributable. No Supabase database or production configuration changes are part of this setup.

## 9. Troubleshooting

- **Maven cannot resolve dependencies:** Check network access to Maven Central, then rerun `mvn dependency:resolve`.
- **PDFBox reports missing classes:** Confirm `pdfbox`, `fontbox`, and `pdfbox-io` resolve at 3.0.8 with
  `mvn dependency:tree`.
- **Ollama API is unavailable:** Start or repair Ollama, then check `ollama list` and
  `Invoke-RestMethod http://localhost:11434/api/tags`. BRED's non-AI features do not depend on it.
- **Tesseract command is not found:** Use `(Get-Command tesseract.exe).Source` after installation; add the install
  directory to `PATH` or set `RMS_TESSERACT_EXE` for the BRED process.
- **Environment changes are not visible:** Restart VS Code/BRED so the process inherits updated user environment
  variables.
- **Tess4J reports a missing native DLL:** Verify the external Tesseract installation and Microsoft Visual C++ v14
  x64 Redistributable. Keep native runtime installation separate from BRED's Maven/JAR packaging.
- **Cloud requests are not configured:** Confirm the provider endpoint uses HTTPS and inject the key through
  `RMS_CLOUD_AI_API_KEY`. Do not send private attachments without explicit administrator consent.
