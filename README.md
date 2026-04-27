# Cropit – PDF Crop Box Editor

A [Quasar](https://quasar.dev) (Vue 3 / TypeScript) frontend + [Spring Boot](https://spring.io/projects/spring-boot) backend for visually defining a crop box on PDF page previews and batch-converting all pages to cropped PNGs.

## Purpose

When converting PDF pages to PNG with
```
pdftoppm -png -r 200 -scale-to 1540 input.pdf output
```
the resulting images often contain repetitive headers and footers that degrade
visual-AI (LLM) Markdown extraction quality.  
**Cropit** lets you upload a PDF, visually define a crop rectangle that covers
the actual page content, and then generate consistently cropped PNGs for every
page — all while preserving the important rendering parameters `-r 200 -scale-to 1540`.

See [`DESIGN.md`](DESIGN.md) for the full architecture and design rationale.

---

## Requirements

### Backend

| Tool              | Version  |
|-------------------|----------|
| Java              | 17+      |
| Maven             | 3.6+     |
| poppler-utils     | any      |

Install poppler-utils (provides `pdftoppm` and `pdfinfo`):
```bash
# Debian / Ubuntu
sudo apt install poppler-utils

# macOS
brew install poppler
```

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

1. **Upload a PDF** using the file picker and choose the number of preview pages (1–20).
2. Click **"Upload & Generate Previews"** – the backend generates PNGs via `pdftoppm` and displays them.
3. **Draw a crop box** by clicking and dragging on any preview image.
   - **Move** the rectangle by clicking inside it and dragging.
   - **Resize** it using the eight yellow handles.
   - The rectangle is mirrored across all previews simultaneously.
4. Click **"Render All Pages with Crop Box"** to start the full conversion.
5. Watch the **progress bar** as pages are processed in batches of 10.
6. When done, **download links** for the generated PNGs appear.

