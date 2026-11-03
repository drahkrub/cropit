# Cropit – PDF Crop Box Editor

A [Quasar](https://quasar.dev) (Vue 3 / TypeScript) frontend + [Spring Boot](https://spring.io/projects/spring-boot) backend for visually defining a crop box on PDF page previews and batch-converting all pages to cropped PNGs.

## Purpose

When converting PDF pages to PNG with
```
pdftoppm -png -scale-to 2048 input.pdf output
```
the resulting images often contain repetitive headers and footers that degrade
visual-AI (LLM) Markdown extraction quality.  
**Cropit** lets you upload a PDF, visually define a crop rectangle that covers
the actual page content, and then generate consistently cropped PNGs for every
page. By default, rendering uses only `pdftoppm` with a configurable longer-side
target (default 2048 pixels). An optional two-step method renders at 400 DPI first
and then downsizes the PNGs in place with ImageMagick `mogrify`.

See [`DESIGN.md`](DESIGN.md) for the full architecture and design rationale.

For subsequent PNG-to-text conversion using a vision model on an Ollama server,
see the independent [png-to-text project](png-to-text/README.md).

---

## Requirements

### Backend

| Tool              | Version  |
|-------------------|----------|
| Java              | 17+      |
| Maven             | 3.6+     |
| poppler-utils     | any      |
| ImageMagick       | optional; required for the two-step method |

Install poppler-utils (provides `pdftoppm` and `pdfinfo`):
```bash
# Debian / Ubuntu
sudo apt install poppler-utils

# macOS
brew install poppler
```

For the optional two-step method, also install ImageMagick (`sudo apt install
imagemagick` on Debian/Ubuntu or `brew install imagemagick` on macOS).

### Frontend

| Tool  | Version |
|-------|---------|
| Node  | 20 – 28 |
| npm   | ≥ 6.14  |

---

## Quick Start (development)

### 1 – Start the backend

```bash
cd backend
mvn spring-boot:run
# Listens on http://localhost:8080
```

### Configure image size

`CROPIT_TARGET_LONG_SIDE` sets the longer side of both preview and cropped output
PNGs in pixels. The default is **2048**. In two-step mode this is a maximum;
smaller images are not enlarged. Set a positive integer before starting
the backend; restart the backend after changing it. The shorter side follows the
aspect ratio.

```bash
cd backend
CROPIT_TARGET_LONG_SIDE=3072 mvn spring-boot:run
```

Alternatively, set the Spring property `cropit.render.target-long-side`, for example:

```bash
java -Dcropit.render.target-long-side=3072 -jar target/cropit-backend-0.0.1-SNAPSHOT.jar
```

### Choose the conversion method

Select **Conversion method** before uploading a PDF:

1. **pdftoppm only (direct)** — default. Uses `-scale-to` to reach the target
   pixel size, including for cropped output. Does not require ImageMagick.
2. **pdftoppm + mogrify (two steps)** — rasterises at the configured DPI, then
   runs `mogrify -resize '2048x2048>'` (for a target of 2048). The DPI defaults to
   **400** and can be changed with `CROPIT_RENDER_DPI` or `cropit.render.dpi`.

The method is saved with the job and applies to both previews and final output.
Upload the PDF again to change the method for an existing job. API clients can
set the multipart field `conversionMode` to `PDFTOPPM` or `PDFTOPPM_MOGRIFY`;
omitting it selects `PDFTOPPM`.

### 2 – Start the frontend dev server

```bash
cd frontend
npm install
npm run dev
# Opens at http://localhost:9000
# /api/* requests are proxied to http://localhost:8080
```

---

## Build

### Backend (fat JAR)

```bash
cd backend
mvn package -DskipTests
java -jar target/cropit-backend-0.0.1-SNAPSHOT.jar
```

### Frontend (production SPA)

```bash
cd frontend
npm run build
# Output in frontend/dist/spa/
```

---

## Lint / type-check (frontend)

```bash
cd frontend
npm run lint
```

---

## Usage

1. **Upload a PDF** using the file picker and choose the number of preview pages (1–20, default 16) and conversion method (default: pdftoppm only).
2. Click **"Upload & Generate Previews"** – the backend generates PNGs via `pdftoppm` (in parallel batches of 4 pages) and displays them.
3. A **crop box is created automatically** after the preview images load.
   - **Move** the rectangle by clicking inside it and dragging.
   - **Resize** it using the eight yellow handles.
   - Clicking outside the rectangle does **not** replace it with a new crop box.
   - The rectangle is mirrored across all previews simultaneously.
4. Click **"Render All Pages with Crop Box"** to start the full conversion.
5. Watch the **progress bar** as pages are processed in parallel batches of 10.
6. When done, **download links** for the generated PNGs appear.
