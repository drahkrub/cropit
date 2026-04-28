# Cropit – Architecture & Design Document

## Overview

Cropit is a tool for converting PDF pages to cropped PNG images, optimised for
visual AI (LLM) preprocessing workflows. The user uploads a PDF, visually selects
a crop region that covers the relevant page content (e.g. skipping headers and
footers), and then triggers a batch conversion of all pages. The result is a set
of consistently cropped PNGs that can be fed to downstream AI pipelines.

---

## Overall Workflow

```
User                     Quasar Frontend               Spring Boot Backend
 │                             │                               │
 │ 1. Select PDF + count       │                               │
 │ ──────────────────────────► │                               │
 │                             │ POST /api/upload              │
 │                             │ ──────────────────────────── ►│
 │                             │                  (pdftoppm)   │
 │                             │ ◄────────────────────────────-│
 │                             │ { jobId, previews[], … }      │
 │                             │                               │
 │ 2. Preview images shown     │                               │
 │    Draw crop box            │                               │
 │                             │                               │
 │ 3. Click "Render"           │                               │
 │                             │ POST /api/jobs/{id}/render    │
 │                             │ { x, y, w, h }                │
 │                             │ ──────────────────────────── ►│
 │                             │                  (async)      │
 │                             │ ◄─────────────────────────── │
 │                             │ 202 Accepted                  │
 │                             │                               │
 │ 4. Progress bar             │ GET /api/jobs/{id}/status     │
 │    (polls every 1.5 s)      │ ──────────────────────────── ►│
 │                             │ ◄─────────────────────────── │
 │                             │ { state, donePages, total, … }│
 │                             │                               │
 │ 5. Done – output file links │                               │
```

---

## Backend Architecture

### Technology Stack

| Component | Choice | Reason |
|-----------|--------|--------|
| Language  | Java 17 | Available everywhere, robust stdlib |
| Framework | Spring Boot 3.2 | Minimal setup for REST + file serving |
| Build     | Maven | Standard Java build tool |

### Module Structure

```
backend/src/main/java/com/cropit/backend/
├── CropitApplication.java          – Main Spring Boot entry point
├── config/
│   └── WebConfig.java              – CORS configuration for dev
├── controller/
│   └── CropController.java         – REST endpoints
├── model/
│   ├── JobState.java               – Enum: PENDING / PREVIEWS_READY / RENDERING / DONE / ERROR
│   ├── CropJob.java                – Mutable job state (volatile fields + AtomicInteger)
│   └── CropBoxRequest.java         – JSON request body for the render endpoint
└── service/
    ├── JobService.java             – Creates and retrieves CropJob instances
    └── PdfService.java             – Executes pdftoppm / pdfinfo via ProcessBuilder
```

### REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/upload` | Upload PDF + choose preview count (1–20). Synchronously generates preview PNGs and returns their URLs. |
| `GET`  | `/api/jobs/{jobId}/status` | Returns current state, progress counters, and output file URLs. |
| `POST` | `/api/jobs/{jobId}/render` | Accepts normalised crop box, starts async full-page render. Returns `202 Accepted`. |
| `GET`  | `/api/jobs/{jobId}/files/preview/{filename}` | Serves a generated preview PNG. |
| `GET`  | `/api/jobs/{jobId}/files/output/{filename}` | Serves a generated output PNG. |

---

## Use of `pdfinfo` and `pdftoppm`

Both external tools are invoked via `java.lang.ProcessBuilder`. stdout and stderr
are merged (`redirectErrorStream(true)`) and captured. A non-zero exit code is
treated as an error and propagated to the client.

### pdfinfo – Determining Total Page Count

```
pdfinfo input.pdf
```

The output is parsed line-by-line; the line starting with `"Pages:"` yields the
total page count. This value is used to:

1. Determine how many preview pages to generate (`min(previewCount, totalPages)`).
2. Drive the batch-render loop and progress calculation.

### pdftoppm – Preview Generation

```
pdftoppm -png -r 200 -scale-to 1540 -f 1 -l <N> input.pdf <jobDir>/preview/page
```

- `-png` – output format
- `-r 200` – render at 200 DPI
- `-scale-to 1540` – scale so the larger dimension is exactly 1540 px
- `-f 1 -l N` – only render pages 1 through N (the requested preview count)

Output files follow the naming convention `page-001.png`, `page-002.png`, etc.

After generation, the natural pixel dimensions of the first PNG are read from the
file (via the PNG IHDR header bytes) and stored in `CropJob`. These dimensions are
needed later for the crop-coordinate conversion.

### pdftoppm – Full-Page Render with Crop

```
pdftoppm -png -r 200 -scale-to 1540 \
         -x <cropX> -y <cropY> -W <cropW> -H <cropH> \
         -f <start> -l <end> \
         input.pdf <jobDir>/output/page
```

The `-r 200 -scale-to 1540` parameters are **always preserved**, ensuring the
rendered pages are identical in scale to the previews. The `-x/-y/-W/-H` flags
restrict each page to the selected crop region.

---

## Batching & Progress Strategy

Full-page rendering can take a long time for large PDFs. To allow progress
reporting without complex multi-threading, the render is broken into **batches of
10 pages**:

```java
for (int start = 1; start <= total; start += BATCH_SIZE) {
    int end = Math.min(start + BATCH_SIZE - 1, total);
    // run pdftoppm for pages start..end
    job.getDonePages().set(end);
}
```

After each batch, `CropJob.donePages` is updated atomically. The frontend polls
`GET /api/jobs/{id}/status` every 1.5 seconds and renders a progress bar:

```
donePages / totalPages
```

The entire render loop runs on a single dedicated daemon thread
(`Executors.newSingleThreadExecutor()`). This keeps the design simple while
ensuring that the HTTP thread never blocks.

---

## Crop-Coordinate Conversion Approach

The Quasar frontend stores the crop box in **normalised coordinates** (values in
`[0, 1]`) relative to the dimensions of the displayed preview container. Because
the preview image fills the container with `width: 100%; height: auto`, the
displayed proportions match the image's natural pixel dimensions.

The conversion to pixel coordinates for `pdftoppm -x/-y/-W/-H` is:

```
cropX_px = round(cropBox.x * previewImageWidth)
cropY_px = round(cropBox.y * previewImageHeight)
cropW_px = round(cropBox.w * previewImageWidth)
cropH_px = round(cropBox.h * previewImageHeight)
```

where `previewImageWidth` and `previewImageHeight` are the pixel dimensions of the
first generated preview PNG (stored in `CropJob` after preview generation).

Because the full render uses **the same `-r 200 -scale-to 1540` parameters**, the
output pixel dimensions for each page are identical to the preview dimensions.
Therefore, the same pixel-crop values apply correctly to all pages.

> **Assumption:** All pages in the PDF have the same dimensions. If they differ,
> the crop region in normalised coordinates will be applied relative to the first
> page's pixel size. Future work could detect per-page dimensions and adjust.

---

## File & Job Storage Approach

Each upload creates a **job directory** under a freshly created temporary
directory (`Files.createTempDirectory("cropit-jobs")`):

```
/tmp/cropit-jobsXXXXXX/
└── {jobId}/
    ├── input.pdf          – uploaded PDF
    ├── preview/           – preview PNGs (page-001.png, …)
    └── output/            – cropped output PNGs (page-001.png, …)
```

Jobs are tracked in a `ConcurrentHashMap<String, CropJob>` keyed by UUID. File
serving is handled by Spring Boot (the controller resolves the file path from the
job directory, validates the filename to prevent path traversal, and streams it
as `image/png`).

---

## Dev Proxy Configuration

During development, the Quasar dev server (default port 9000) proxies all
`/api/*` requests to `http://localhost:8080` (the Spring Boot backend). This
avoids CORS issues and mimics the production layout where both would be behind
the same origin.

In addition, the backend enables CORS for `localhost:*` via `WebConfig` as a
safety net.

---

## Assumptions, Limitations & Future Improvements

### Assumptions

- `pdftoppm` and `pdfinfo` (from the **poppler-utils** package) are installed and
  on the system PATH.
- All PDF pages have the same physical dimensions.
- The server runs as a single instance (in-memory job map).
- The operating system's temp directory has sufficient disk space.

### Limitations

- **No persistence**: Jobs are lost on server restart.
- **No cleanup**: Temporary files accumulate; a scheduled cleanup job should be
  added for production use.
- **No authentication**: The API is open; any client can upload PDFs and trigger
  renders.
- **Single-page-size assumption**: Mixed-size PDFs may produce misaligned crops on
  pages that differ from the first page's dimensions.
- **Large file uploads**: The default limit is set to 200 MB; very large PDFs may
  still time out depending on available memory and disk I/O.
- **No download ZIP**: Output PNGs are listed individually; a batch-download
  endpoint (e.g. returning a ZIP archive) would improve usability.

### Future Improvements

- Persist jobs to a database (e.g. H2 / PostgreSQL) for durability.
- Implement a scheduled cleanup task to remove old job directories.
- Add user authentication (e.g. Spring Security + JWT).
- Support mixed-page-size PDFs by reading per-page dimensions via `pdfinfo -f N`.
- Provide a ZIP-download endpoint for the rendered output.
- Add a Dockerfile / Docker Compose setup for easy deployment.
- Write integration tests using `@SpringBootTest` + a bundled test PDF.
