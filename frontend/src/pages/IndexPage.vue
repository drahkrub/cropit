<template>
  <q-page class="q-pa-md">

    <!-- ─── Step 1: Upload PDF ──────────────────────────────────────────────── -->
    <q-card flat bordered class="q-mb-md">
      <q-card-section>
        <div class="text-subtitle1 q-mb-sm">Step 1 – Upload PDF</div>
        <div class="row q-gutter-sm items-end">
          <q-file
            v-model="selectedFile"
            label="Select PDF file"
            accept=".pdf"
            outlined
            dense
            class="col-12 col-md-5"
            :disable="uploading"
          >
            <template #prepend>
              <q-icon name="picture_as_pdf" />
            </template>
          </q-file>

          <div class="col-12 col-md-3">
            <div class="text-caption text-grey-7 q-mb-xs">
              Preview pages: {{ previewCount }}
            </div>
            <q-slider
              v-model="previewCount"
              :min="1"
              :max="20"
              :step="1"
              label
              color="primary"
              :disable="uploading"
            />
          </div>

          <q-btn
            color="primary"
            icon="upload"
            label="Upload & Generate Previews"
            :loading="uploading"
            :disable="!selectedFile || uploading"
            class="col-12 col-md-auto"
            @click="uploadPdf"
          />
        </div>

        <div v-if="uploadError" class="text-negative q-mt-sm">
          {{ uploadError }}
        </div>
      </q-card-section>
    </q-card>

    <!-- ─── Step 2: Preview grid ────────────────────────────────────────────── -->
    <template v-if="urls.length > 0">
      <q-card flat bordered class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1 q-mb-sm">
            Step 2 – Define Crop Box
            <span class="text-grey-6 text-body2 q-ml-sm">
              (drag to draw, move, or resize the yellow rectangle across all previews)
            </span>
          </div>

          <!-- Horizontal scroll container for variable number of previews -->
          <div class="preview-scroll-wrapper">
            <div class="preview-row">
              <div
                v-for="(url, i) in urls"
                :key="i"
                class="preview-col"
              >
                <CropPreview
                  :imageUrl="url"
                  :cropBox="cropBox"
                  :label="`Page ${previewPageIndices[i] ?? i + 1}`"
                  @update:cropBox="onCropUpdate"
                  @imageSize="(w, h) => onImageSize(i, w, h)"
                />
              </div>
            </div>
          </div>
        </q-card-section>
      </q-card>

      <!-- ─── Crop values ───────────────────────────────────────────────── -->
      <q-card flat bordered class="q-mb-md">
        <q-card-section>
          <div class="row items-center q-mb-sm">
            <div class="text-subtitle1 col">Crop Box Values</div>
            <q-btn
              v-if="cropBox"
              flat
              dense
              icon="clear"
              label="Clear crop"
              color="negative"
              @click="clearCrop"
            />
          </div>

          <div v-if="cropBox" class="row q-gutter-md">
            <!-- Normalised values -->
            <q-card flat bordered class="col-12 col-md-5">
              <q-card-section>
                <div class="text-caption text-grey-7 q-mb-xs">Normalised (0&hellip;1)</div>
                <div class="mono-grid">
                  <span>x</span><span>{{ fmt(cropBox.x) }}</span>
                  <span>y</span><span>{{ fmt(cropBox.y) }}</span>
                  <span>w</span><span>{{ fmt(cropBox.w) }}</span>
                  <span>h</span><span>{{ fmt(cropBox.h) }}</span>
                </div>
              </q-card-section>
            </q-card>

            <!-- Pixel values (uses size of first loaded image) -->
            <q-card flat bordered class="col-12 col-md-5">
              <q-card-section>
                <div class="text-caption text-grey-7 q-mb-xs">
                  Pixels
                  <span v-if="refSize">
                    ({{ refSize.w }} &times; {{ refSize.h }} px)
                  </span>
                  <span v-else class="text-grey-5">(load an image for pixel values)</span>
                </div>
                <template v-if="refSize">
                  <div class="mono-grid">
                    <span>x</span><span>{{ px(cropBox.x, refSize.w) }}</span>
                    <span>y</span><span>{{ px(cropBox.y, refSize.h) }}</span>
                    <span>w</span><span>{{ px(cropBox.w, refSize.w) }}</span>
                    <span>h</span><span>{{ px(cropBox.h, refSize.h) }}</span>
                  </div>
                </template>
                <div v-else class="text-grey-5 text-caption">&mdash;</div>
              </q-card-section>
            </q-card>
          </div>

          <div v-else class="text-grey-5 text-body2">
            Draw a crop rectangle on any preview image to get started.
          </div>
        </q-card-section>
      </q-card>

      <!-- ─── Step 3: Render ──────────────────────────────────────────────── -->
      <q-card flat bordered class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1 q-mb-sm">Step 3 – Render All Pages</div>

          <q-btn
            color="secondary"
            icon="auto_fix_high"
            label="Render All Pages with Crop Box"
            :disable="!cropBox || rendering || jobState === 'DONE'"
            :loading="rendering"
            @click="startRender"
          />
          <span v-if="!cropBox" class="q-ml-sm text-grey-6 text-caption">
            (define a crop box first)
          </span>
          <span v-if="renderError" class="q-ml-sm text-negative text-caption">
            {{ renderError }}
          </span>

          <!-- Progress -->
          <template v-if="rendering || jobState === 'DONE' || jobState === 'ERROR'">
            <div class="q-mt-md">
              <div class="row items-center q-gutter-sm q-mb-xs">
                <span class="text-caption">
                  Pages rendered: {{ donePages }} / {{ totalPages }}
                </span>
                <q-badge
                  :color="stateColor"
                  :label="jobState"
                />
              </div>
              <q-linear-progress
                :value="totalPages > 0 ? donePages / totalPages : 0"
                :color="stateColor"
                rounded
                class="q-mt-xs"
                style="height: 10px"
              />
            </div>

            <div v-if="jobState === 'DONE'" class="q-mt-md">
              <div class="text-positive q-mb-sm">
                <q-icon name="check_circle" class="q-mr-xs" />
                Rendering complete &ndash; {{ outputFiles.length }} PNG(s) generated.
              </div>
              <div class="text-caption text-grey-6 q-mb-xs">
                Output files (served by the backend):
              </div>
              <ul class="output-file-list">
                <li v-for="url in outputFiles" :key="url">
                  <a :href="url" target="_blank">{{ url.split('/').pop() }}</a>
                </li>
              </ul>
            </div>

            <div v-if="jobState === 'ERROR'" class="q-mt-sm text-negative">
              <q-icon name="error" class="q-mr-xs" />
              {{ renderError || 'An unknown error occurred.' }}
            </div>
          </template>
        </q-card-section>
      </q-card>
    </template>

  </q-page>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue';
import type { CropBox } from 'components/models';
import CropPreview from 'components/CropPreview.vue';

// ---------------------------------------------------------------------------
// Upload state
// ---------------------------------------------------------------------------
const selectedFile = ref<File | null>(null);
const previewCount = ref<number>(4);
const uploading = ref(false);
const uploadError = ref<string | null>(null);

// ---------------------------------------------------------------------------
// Job state
// ---------------------------------------------------------------------------
const jobId = ref<string | null>(null);

// ---------------------------------------------------------------------------
// Preview URLs and page mapping
// ---------------------------------------------------------------------------
const urls = ref<string[]>([]);
const previewPageIndices = ref<number[]>([]);

// ---------------------------------------------------------------------------
// Crop state
// ---------------------------------------------------------------------------
const cropBox = ref<CropBox | null>(null);

function onCropUpdate(box: CropBox) {
  cropBox.value = { ...box };
}

function clearCrop() {
  cropBox.value = null;
}

// ---------------------------------------------------------------------------
// Image sizes (indexed by preview position)
// ---------------------------------------------------------------------------
interface Size { w: number; h: number }
const imageSizes = ref<(Size | null)[]>([]);

function onImageSize(index: number, w: number, h: number) {
  imageSizes.value[index] = { w, h };
}

/** Use the first available image size as the reference for pixel conversion */
const refSize = computed(() => imageSizes.value.find(Boolean) ?? null);

// ---------------------------------------------------------------------------
// Render state
// ---------------------------------------------------------------------------
const rendering = ref(false);
const renderError = ref<string | null>(null);
const jobState = ref<string>('');
const donePages = ref(0);
const totalPages = ref(0);
const outputFiles = ref<string[]>([]);

let pollTimer: ReturnType<typeof setInterval> | null = null;
/** Stop polling after 30 minutes to avoid indefinite polling if backend crashes. */
const MAX_POLL_COUNT = 1200;
let pollCount = 0;

const stateColor = computed(() => {
  if (jobState.value === 'DONE') return 'positive';
  if (jobState.value === 'ERROR') return 'negative';
  return 'primary';
});

// ---------------------------------------------------------------------------
// Upload & preview generation
// ---------------------------------------------------------------------------
async function uploadPdf() {
  if (!selectedFile.value) return;

  uploading.value = true;
  uploadError.value = null;
  urls.value = [];
  previewPageIndices.value = [];
  imageSizes.value = [];
  cropBox.value = null;
  jobId.value = null;
  jobState.value = '';
  donePages.value = 0;
  totalPages.value = 0;
  outputFiles.value = [];
  renderError.value = null;
  stopPolling();

  const formData = new FormData();
  formData.append('file', selectedFile.value);
  formData.append('previewCount', String(previewCount.value));

  try {
    const res = await fetch('/api/upload', {
      method: 'POST',
      body: formData,
    });

    const data = await res.json() as {
      jobId?: string;
      previews?: { url: string; filename: string }[];
      imageWidth?: number;
      imageHeight?: number;
      totalPages?: number;
      error?: string;
    };

    if (!res.ok) {
      uploadError.value = data.error ?? `Upload failed (HTTP ${res.status})`;
      return;
    }

    jobId.value = data.jobId ?? null;
    totalPages.value = data.totalPages ?? 0;
    imageSizes.value = [];

    const previewUrls: string[] = [];
    const pageIdxs: number[] = [];
    const previews = data.previews ?? [];
    previews.forEach((p, i) => {
      previewUrls.push(p.url);
      pageIdxs.push(i + 1);
      imageSizes.value.push(null);
    });
    urls.value = previewUrls;
    previewPageIndices.value = pageIdxs;

  } catch (err) {
    uploadError.value = err instanceof Error ? err.message : 'Unknown error during upload';
  } finally {
    uploading.value = false;
  }
}

// ---------------------------------------------------------------------------
// Start render
// ---------------------------------------------------------------------------
async function startRender() {
  if (!jobId.value || !cropBox.value) return;

  rendering.value = true;
  renderError.value = null;
  jobState.value = 'RENDERING';
  donePages.value = 0;
  outputFiles.value = [];

  try {
    const res = await fetch(`/api/jobs/${jobId.value}/render`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        x: cropBox.value.x,
        y: cropBox.value.y,
        w: cropBox.value.w,
        h: cropBox.value.h,
      }),
    });

    const data = await res.json() as { message?: string; error?: string };

    if (!res.ok) {
      renderError.value = data.error ?? `Render start failed (HTTP ${res.status})`;
      rendering.value = false;
      jobState.value = 'ERROR';
      return;
    }

    // Start polling for progress
    startPolling();

  } catch (err) {
    renderError.value = err instanceof Error ? err.message : 'Unknown error starting render';
    rendering.value = false;
    jobState.value = 'ERROR';
  }
}

// ---------------------------------------------------------------------------
// Progress polling
// ---------------------------------------------------------------------------
function startPolling() {
  if (pollTimer !== null) return;
  pollCount = 0;
  pollTimer = setInterval(() => { void pollStatus(); }, 1500);
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function pollStatus() {
  if (!jobId.value) return;

  pollCount++;
  if (pollCount > MAX_POLL_COUNT) {
    renderError.value = 'Polling timed out – the backend may be unavailable. Please check the server.';
    rendering.value = false;
    jobState.value = 'ERROR';
    stopPolling();
    return;
  }

  try {
    const res = await fetch(`/api/jobs/${jobId.value}/status`);
    if (!res.ok) return;

    const data = await res.json() as {
      state: string;
      donePages: number;
      totalPages: number;
      outputFiles: string[];
      errorMessage?: string | null;
    };

    jobState.value = data.state;
    donePages.value = data.donePages;
    totalPages.value = data.totalPages;
    outputFiles.value = data.outputFiles ?? [];

    if (data.state === 'DONE') {
      rendering.value = false;
      stopPolling();
    } else if (data.state === 'ERROR') {
      renderError.value = data.errorMessage ?? 'Render failed';
      rendering.value = false;
      stopPolling();
    }

  } catch {
    // Silently ignore transient network errors during polling
  }
}

// Stop polling when component is destroyed
onUnmounted(stopPolling);

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------
function fmt(v: number) {
  return v.toFixed(4);
}

function px(norm: number, size: number) {
  return `${Math.round(norm * size)} px`;
}
</script>

<style scoped lang="scss">
/* Horizontal scrolling container for preview images */
.preview-scroll-wrapper {
  overflow-x: auto;
  overflow-y: visible;
}

.preview-row {
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  /* Ensure the row is at least as wide as needed so images are side-by-side */
  min-width: min-content;
}

.preview-col {
  /* Each preview takes a fixed minimum width; images are never squashed below this.
     Set to ~double the original size so the crop box can be placed precisely. */
  flex: 0 0 560px;
  max-width: 800px;
}

.mono-grid {
  display: grid;
  grid-template-columns: 1.5rem 1fr;
  gap: 2px 8px;
  font-family: monospace;
  font-size: 13px;
}

.output-file-list {
  list-style: disc;
  padding-left: 1.2rem;
  margin: 4px 0;
  font-size: 13px;
  font-family: monospace;
  max-height: 200px;
  overflow-y: auto;
}
</style>
