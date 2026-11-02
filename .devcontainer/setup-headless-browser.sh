#!/usr/bin/env bash
# .devcontainer/setup-headless-browser.sh
# Installiert einen Headless-Chromium, mit dem Claude Code die UI selbst
# testen kann (Screenshots, DOM-Dumps, Klickpfade) statt nur zu behaupten,
# dass etwas funktioniert.
#
# Bewusst OHNE "playwright" als Projekt-Dependency: Das Skript traegt nichts in
# package.json oder Lockfile ein -- wichtig, weil die Vorlage nicht weiss, mit
# welchem Paketmanager das Projekt arbeitet, und ein versehentlicher Eintrag
# sonst im falschen Lockfile landen koennte.
#
# Welches Playwright installiert:
#
#   Projekt bringt Playwright mit (z.B. @playwright/test fuer Browsertests,
#   erkennbar an node_modules/.bin/playwright): dessen Version. Jede
#   Playwright-Version erwartet ihre eigene Browser-Revision
#   (~/.cache/ms-playwright/chromium_headless_shell-<revision>); so passt der
#   Browser nach jedem Rebuild zum Lockfile, ohne dass hier eine Version steht.
#   Mehrere Projekte mit Playwright: jedes installiert seine.
#
#   Sonst: die neueste Version, per pnpm dlx nur voruebergehend geladen.
#
# Laeuft bei jedem Rebuild in postCreate.sh, also bevor postStartCommand die
# Firewall startet. Das ist noetig: cdn.playwright.dev leitet die Downloads
# auf storage.googleapis.com um, das nicht auf der Allowlist steht. Erledigt
# unterschiedlich viel:
#
#   install-deps  installiert Systembibliotheken per apt. Die liegen im
#                 Container-Dateisystem und sind nach jedem Rebuild weg,
#                 muessen also jedes Mal neu. Das ist der schnelle Teil.
#   install       laedt die Browser nach ~/.cache/ms-playwright (656 MB).
#                 Seit ~/.cache ein eigenes Volume ist (siehe
#                 devcontainer.json), ueberlebt das den Rebuild; der Befehl
#                 prueft den Bestand und ist dann in Sekunden durch.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# $@: der Playwright-Aufruf, z.B. "pnpm dlx playwright" oder der Pfad zum
# Playwright des Projekts
install_browsers() {
  echo "==> Installiere Systembibliotheken fuer Headless-Chromium (Schriften, libnss etc.)"
  CI=true "$@" install-deps chromium

  echo "==> Installiere Chromium + Chromium-Headless-Shell"
  # Beide Namen angeben: "chromium" allein laesst die Headless-Shell fehlen, die
  # chromium.launch() tatsaechlich startet.
  CI=true "$@" install chromium chromium-headless-shell
}

# node_modules bis Tiefe 5 (z.B. src/main/frontend2/node_modules), ohne in sie
# hineinzusteigen
found=0
while IFS= read -r node_modules; do
  playwright="${node_modules}/.bin/playwright"
  if [ -x "${playwright}" ]; then
    found=1
    echo "==> Playwright des Projekts: ${playwright#"${PROJECT_DIR}/"} ($("${playwright}" --version))"
    install_browsers "${playwright}"
  fi
done < <(find "${PROJECT_DIR}" -maxdepth 5 -type d -name node_modules -prune -print)

if [ "${found}" = 0 ]; then
  echo "==> Kein Playwright im Projekt, nehme die neueste Version"
  install_browsers pnpm dlx playwright
fi

echo "==> Headless-Browser-Setup abgeschlossen"
