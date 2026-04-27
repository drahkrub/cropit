<template>
  <q-layout view="hHh lpR fFf">
    <q-header elevated>
      <q-toolbar>
        <q-icon name="crop" size="sm" class="q-mr-sm" />
        <q-toolbar-title>Cropit – PDF Crop Box Editor</q-toolbar-title>
        <div class="text-caption text-grey-4">Quasar v{{ $q.version }}</div>
      </q-toolbar>
    </q-header>

    <q-page-container>
      <router-view />
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { useQuasar } from 'quasar';
const $q = useQuasar();
</script>

