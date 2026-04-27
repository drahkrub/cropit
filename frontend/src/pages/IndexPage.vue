<template>
  <q-page class="q-pa-md">
    <!-- ─── URL Inputs ─────────────────────────────────────────────────── -->
    <q-card flat bordered class="q-mb-md">
      <q-card-section>
        <div class="text-subtitle1 q-mb-sm">Page Image URLs</div>
        <div class="row q-gutter-sm">
          <q-input
            v-for="(_, i) in urls"
            :key="i"
            v-model="urls[i]"
            :label="`Page ${i + 1}`"
            outlined
            dense
            clearable
            class="col-12 col-md-6 col-lg-3"
            :hint="imageSizes[i] ? `${imageSizes[i]!.w} × ${imageSizes[i]!.h} px` : 'Enter image URL'"
          />
        </div>
      </q-card-section>
    </q-card>

    <!-- ─── Preview grid ──────────────────────────────────────────────── -->
    <div class="row no-wrap q-gutter-sm q-mb-md">
      <div
        v-for="(url, i) in urls"
        :key="i"
        class="col"
        style="min-width: 0"
      >
        <CropPreview
          :imageUrl="url"
          :cropBox="cropBox"
          :label="`Page ${i + 1}`"
          @update:cropBox="onCropUpdate"
          @imageSize="(w, h) => onImageSize(i, w, h)"
        />
      </div>
    </div>

    <!-- ─── Crop values ───────────────────────────────────────────────── -->
    <q-card flat bordered>
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
              <div class="text-caption text-grey-7 q-mb-xs">Normalised (0…1)</div>
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
                  ({{ refSize.w }} × {{ refSize.h }} px)
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
              <div v-else class="text-grey-5 text-caption">—</div>
            </q-card-section>
          </q-card>

          <!-- JSON output -->
          <q-card flat bordered class="col-12">
            <q-card-section>
              <div class="text-caption text-grey-7 q-mb-xs">JSON</div>
              <pre class="crop-json">{{ cropJson }}</pre>
            </q-card-section>
          </q-card>
        </div>

        <div v-else class="text-grey-5 text-body2">
          Draw a crop rectangle on any preview image to get started.
        </div>
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import type { CropBox } from 'components/models';
import CropPreview from 'components/CropPreview.vue';

// ---------------------------------------------------------------------------
// URLs – pre-filled with placeholder Picsum images for easy demo
// ---------------------------------------------------------------------------
const urls = ref<string[]>([
  'https://picsum.photos/seed/p1/800/1131',
  'https://picsum.photos/seed/p2/800/1131',
  'https://picsum.photos/seed/p3/800/1131',
  'https://picsum.photos/seed/p4/800/1131',
]);

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
// Image sizes (indexed by page position)
// ---------------------------------------------------------------------------
interface Size { w: number; h: number }
const imageSizes = ref<(Size | null)[]>([null, null, null, null]);

function onImageSize(index: number, w: number, h: number) {
  imageSizes.value[index] = { w, h };
}

/** Use the first available image size as the reference for pixel conversion */
const refSize = computed(() => imageSizes.value.find(Boolean) ?? null);

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------
function fmt(v: number) {
  return v.toFixed(4);
}

function px(norm: number, size: number) {
  return `${Math.round(norm * size)} px`;
}

const cropJson = computed(() => {
  if (!cropBox.value) return '';
  return JSON.stringify(cropBox.value, null, 2);
});
</script>

<style scoped lang="scss">
.mono-grid {
  display: grid;
  grid-template-columns: 1.5rem 1fr;
  gap: 2px 8px;
  font-family: monospace;
  font-size: 13px;
}

.crop-json {
  margin: 0;
  font-family: monospace;
  font-size: 12px;
  background: #f5f5f5;
  padding: 8px 12px;
  border-radius: 4px;
  overflow-x: auto;
}

body.body--dark .crop-json {
  background: #2a2a2a;
}
</style>

