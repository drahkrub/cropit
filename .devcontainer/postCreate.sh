#!/usr/bin/env bash
# .devcontainer/postCreate.sh
# Laeuft einmalig, nachdem der Dev Container erstellt (bzw. rebuilt) wurde.
#
# Reihenfolge ist Absicht: alles, was aus dem Netz laedt, passiert HIER --
# also bevor init-firewall.sh beim ersten postStart die Egress-Firewall
# hochzieht. Was danach noch aus dem Netz muss, gehoert in
# allowed-domains.txt.
set -euo pipefail

# Besitzrechte der gemounteten Volumes richtigstellen. Frisch angelegte
# Volumes gehoeren root:root, nicht dem remoteUser -- und bei ~/.claude faellt
# das besonders unangenehm auf: .credentials.json hat Modus 600, ist also bei
# fremdem Eigentuemer nicht lesbar, und Claude Code verlangt eine neue
# Anmeldung statt einer Fehlermeldung.
#
# Das darf hier pauschal passieren, weil ALLE Volumes projekteigen sind
# (claude-<projekt>-home/-m2/-cache/-codex/-node-modules) und keinem zweiten
# Container gehoeren. Bei einem geteilten Volume verboete sich das: ein chown
# uebernaehme dessen Dateien, der andere Container chownte beim naechsten
# Start zurueck.
#
# node_modules nur, wenn es wirklich das Volume ist (mountpoint): bei einem
# Projekt ohne Node darf die Zeile in devcontainer.json fehlen, und dann ist
# node_modules -- falls es ueberhaupt existiert -- das Verzeichnis des Hosts.
#
# NODE_DIR: das Frontend liegt in diesem Repo nicht neben .devcontainer/,
# sondern in src/main/frontend. Muss zum node_modules-Mount in
# devcontainer.json passen.
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NODE_DIR="${PROJECT_DIR}/src/main/frontend"
VOLUME_DIRS=("${HOME}/.claude" "${HOME}/.m2" "${HOME}/.cache" "${HOME}/.codex")
if mountpoint -q "${NODE_DIR}/node_modules"; then
  VOLUME_DIRS+=("${NODE_DIR}/node_modules")
fi
for vol_dir in "${VOLUME_DIRS[@]}"; do
  owner_uid="$(stat -c %u "$vol_dir")"
  if [ "$owner_uid" != "$(id -u)" ]; then
    echo "==> ${vol_dir} gehoert uid ${owner_uid}, wir laufen als uid $(id -u) -- korrigiere"
    sudo chown -R "$(id -u):$(id -g)" "$vol_dir"
  fi
done
CLAUDE_DIR="${HOME}/.claude"

echo "==> Installiere Werkzeuge (Firewall + Suche)"
# iptables/ipset: Regelwerk der Firewall
# dnsutils:       dig, loest die Allowlist-Domains auf
# aggregate:      fasst die GitHub-CIDR-Liste zusammen
# jq:             parst api.github.com/meta
# ripgrep:        schnelle Codesuche, von Claude Code bevorzugt genutzt
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  iptables ipset dnsutils aggregate jq ripgrep vim postgresql-client
sudo rm -rf /var/lib/apt/lists/*

echo "==> Lege die Firewall root-eigen ab"
# Gleich nach ihren Werkzeugen und nicht erst am Ende: postStartCommand
# startet sie von dort. Bricht dieses Skript weiter unten ab, laeuft die
# Firewall trotzdem an (sudo ist dann noch nicht eingeschraenkt).
sudo bash .devcontainer/install-firewall.sh

echo "==> Installiere Hurl (HTTP-Tests, u.a. tests/golden/)"
# Tarball statt .deb: das Binary nutzt libcurl/libxml2 des Systems, die das
# Basisimage schon mitbringt. Version und Pruefsummen sind hier fest
# eingetragen und werden nicht aus dem Release nachgeladen -- sonst prueft
# die Pruefsumme nur, ob der Download vollstaendig ist, nicht ob er stimmt.
# Neue Version: HURL_VERSION anheben und beide *.tar.gz.sha256 des Releases
# eintragen.
HURL_VERSION=8.0.1
case "$(uname -m)" in
  x86_64)  HURL_ARCH=x86_64;  HURL_SHA256=cac7c4670d69444db120edb21fe06c97ba8c80dcc52279957c8dd18f05fb0c06 ;;
  aarch64) HURL_ARCH=aarch64; HURL_SHA256=bc4732df4754748e9bf296aa3832ec019f798afb399f1279b72ed37b6e04525c ;;
  *)       HURL_ARCH="" ;;
esac
if [ -n "${HURL_ARCH}" ]; then
  HURL_PKG="hurl-${HURL_VERSION}-${HURL_ARCH}-unknown-linux-gnu"
  HURL_TMP="$(mktemp -d)"
  curl -fsSL -o "${HURL_TMP}/hurl.tgz" \
    "https://github.com/Orange-OpenSource/hurl/releases/download/${HURL_VERSION}/${HURL_PKG}.tar.gz"
  echo "${HURL_SHA256}  ${HURL_TMP}/hurl.tgz" | sha256sum -c -
  tar xzf "${HURL_TMP}/hurl.tgz" -C "${HURL_TMP}"
  sudo install -m 0755 "${HURL_TMP}/${HURL_PKG}/bin/hurl" "${HURL_TMP}/${HURL_PKG}/bin/hurlfmt" /usr/local/bin/
  rm -rf "${HURL_TMP}"
else
  echo "    (keine Hurl-Binaries fuer $(uname -m) -- uebersprungen)"
fi

echo "==> Installiere delta (Diff-Anzeige fuer git, wie im Referenz-Devcontainer von Claude Code)"
# Wie bei Hurl sind Version und Pruefsummen fest eingetragen. delta veroeffentlicht
# keine eigenen .sha256-Dateien; die Werte sind die SHA-256-Digests, die GitHub zu
# jedem Release-Asset fuehrt. Neue Version: DELTA_VERSION anheben und beide Digests
# eintragen, abzulesen mit
#   curl -s https://api.github.com/repos/dandavison/delta/releases/latest \
#     | jq -r '.assets[] | select(.name | test("^git-delta_[0-9.]+_(amd64|arm64)\\.deb$")) | "\(.name) \(.digest)"'
# Das .deb haengt nur an libc6, libgcc-s1 und zlib1g, die das Basisimage mitbringt;
# dpkg -i genuegt also, apt braucht es nicht.
#
# Als Pager traegt es setup-home.sh ein (weiter unten).
DELTA_VERSION=0.20.1
case "$(dpkg --print-architecture)" in
  amd64) DELTA_ARCH=amd64; DELTA_SHA256=0683067a4cbbcc19b9fd2841253131d1b37ccfa724725098d811d4d0d9fcc25b ;;
  arm64) DELTA_ARCH=arm64; DELTA_SHA256=6e08b734d611037eb9eb5db883cab7ac0a089216a9efdc1d2caac44319029c28 ;;
  *)     DELTA_ARCH="" ;;
esac
if [ -n "${DELTA_ARCH}" ]; then
  DELTA_TMP="$(mktemp -d)"
  DELTA_DEB="${DELTA_TMP}/git-delta_${DELTA_VERSION}_${DELTA_ARCH}.deb"
  curl -fsSL -o "${DELTA_DEB}" \
    "https://github.com/dandavison/delta/releases/download/${DELTA_VERSION}/git-delta_${DELTA_VERSION}_${DELTA_ARCH}.deb"
  echo "${DELTA_SHA256}  ${DELTA_DEB}" | sha256sum -c -
  sudo dpkg -i "${DELTA_DEB}"
  rm -rf "${DELTA_TMP}"
else
  echo "    (kein delta-Paket fuer $(dpkg --print-architecture) -- uebersprungen)"
fi

echo "==> Installiere Claude Code (nativer Installer, kein Node.js noetig)"
curl -fsSL https://claude.ai/install.sh | bash

# Der native Installer legt den Befehl unter ~/.local/bin/claude ab.
# Zusaetzlicher Symlink nach /usr/local/bin, damit "claude" unabhaengig
# von Shell-Rc-Dateien gefunden wird -- also auch bei "docker exec ... claude"
# oder Terminals, die kein Login-Shell-Profil laden.
CLAUDE_BIN="${HOME}/.local/bin/claude"
if [ -f "${CLAUDE_BIN}" ]; then
  sudo ln -sf "${CLAUDE_BIN}" /usr/local/bin/claude
fi

# SDKMAN (vom Java-Feature genutzt) auch in dieser nicht-interaktiven Shell
# verfuegbar machen, damit der Versionscheck unten zuverlaessig funktioniert.
SDKMAN_INIT="${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh"
if [ -s "${SDKMAN_INIT}" ]; then
  set +u; source "${SDKMAN_INIT}"; set -u
fi

# Das Node-Feature installiert yarn ueber Corepack (installYarnUsingApt=false).
# Der Aufruf hier ist nur die Absicherung fuer den Fall, dass der Shim fehlt --
# die Vorlage legt sich nicht auf einen Paketmanager fest, yarn und pnpm
# muessen beide funktionieren.
echo "==> Stelle sicher, dass Corepack aktiv ist (yarn)"
corepack enable 2>/dev/null || sudo corepack enable || echo "    (corepack nicht verfuegbar)"

# Global ins nvm-Verzeichnis, das liegt nicht im Volume. Deshalb bei jedem
# Rebuild neu, die Anmeldung bleibt im Volume ~/.codex.
echo "==> Installiere Codex (OpenAI)"
npm install -g @openai/codex || echo "    WARNUNG: Codex-Installation fehlgeschlagen"

echo "==> Richte MCP-Server ein"
bash .devcontainer/setup-mcp.sh

# Braucht sudo (playwright install-deps ruft apt), muss also vor die Sperre.
echo "==> Richte Headless-Browser fuer UI-Selbsttests ein"
bash .devcontainer/setup-headless-browser.sh

echo "==> Schraenke sudo ein: nur noch die Firewall"
# Bisher durfte "node" per sudo alles. Damit liesse sich die Firewall mit
# einem Befehl abschalten -- fuer einen Agenten, der ohne Rueckfragen laeuft,
# keine Mauer, sondern eine Leitplanke. Ab hier darf "node" nur noch das
# root-eigene init-firewall.sh starten, und zwar ohne Argumente ("").
# Anthropics Referenz-Devcontainer macht es genauso.
#
# Bis hierher braucht dieses Skript sudo (apt, dpkg, chown, Headless-
# Browser); alles danach laeuft ohne -- auch personal.sh aus dem Home-Volume.
# Nach einem Rebuild gilt wieder die Regel aus dem Image (NOPASSWD: ALL),
# dieser Schritt kommt dann von neuem.
#
# Root wird man danach nur noch vom HOST aus:
#   docker exec -it -u root claude-<projekt> bash
# Den Weg hat ein Agent im Container nicht: kein Docker-Socket, kein docker-CLI.
#
# Die Datei pruefen, bevor sie gilt: eine kaputte Regel sperrte sudo ganz,
# auch fuer postStartCommand. Der Punkt im Namen der Hilfsdatei ist Absicht:
# sudo ueberspringt in /etc/sudoers.d alles mit "." im Namen.
SUDO_RULE="$(id -un) ALL=(root) NOPASSWD: /usr/local/lib/devcontainer-firewall/init-firewall.sh \"\""
echo "${SUDO_RULE}" | sudo tee "/etc/sudoers.d/$(id -un).new" >/dev/null
sudo chmod 0440 "/etc/sudoers.d/$(id -un).new"
sudo visudo -cqf "/etc/sudoers.d/$(id -un).new"
sudo mv "/etc/sudoers.d/$(id -un).new" "/etc/sudoers.d/$(id -un)"
echo "    sudo erlaubt nur noch: ${SUDO_RULE#* NOPASSWD: }"
# Die Regel ersetzt nur diese eine Datei. Gibt eine andere (/etc/sudoers,
# Gruppe sudo/admin, weitere Datei) mehr frei, waere alles umsonst.
if sudo -n -l 2>/dev/null | grep -q 'ALL$'; then
  echo "    WARNUNG: sudo erlaubt weiterhin alles -- 'sudo -l' zeigt, welche Regel"
fi

echo "==> Richte Home ein (git, Statuszeile, persoenliche Einstellungen)"
bash .devcontainer/setup-home.sh

echo
echo "==> Versionscheck"
java -version   2>&1 || echo "    (java nicht gefunden -- neues Terminal oeffnen und erneut pruefen)"
mvn -version    2>&1 || echo "    (mvn nicht gefunden -- neues Terminal oeffnen und erneut pruefen)"
node --version  2>&1 || echo "    (node nicht gefunden)"
pnpm --version  2>&1 || echo "    (pnpm nicht gefunden)"
yarn --version  2>&1 || echo "    (yarn nicht gefunden -- 'corepack enable' pruefen)"
claude --version 2>&1 || echo "    (claude nicht gefunden)"
codex --version 2>&1 || echo "    (codex nicht gefunden)"
hurl --version 2>&1 | head -1 || echo "    (hurl nicht gefunden)"
delta --version 2>&1 || echo "    (delta nicht gefunden)"

echo
echo "==> Anmeldung an Claude Code"
if [ -r "${CLAUDE_DIR}/.credentials.json" ]; then
  echo "    Zugangsdaten gefunden und lesbar -- die Anmeldung aus dem"
  echo "    projekteigenen Volume gilt weiter."
else
  echo "    Noch keine (lesbaren) Zugangsdaten in ${CLAUDE_DIR}/.credentials.json."
  echo "    -> Einmal 'claude' aufrufen und dem OAuth-Link folgen; er wird im"
  echo "       Browser des HOSTS geoeffnet. Diese Anmeldung gilt NUR fuer"
  echo "       diesen Container und ueberlebt jeden Rebuild, weil sie im"
  echo "       projekteigenen Volume liegt."
  echo "       Zweiter Zugang? Den Link in einem privaten Browserfenster"
  echo "       oeffnen, sonst tauscht er die claude.ai-Sitzung des Hosts aus."
fi
if [ "${CLAUDE_CONFIG_DIR:-}" != "${CLAUDE_DIR}" ]; then
  echo "    WARNUNG: CLAUDE_CONFIG_DIR ist '${CLAUDE_CONFIG_DIR:-}', erwartet"
  echo "    wird ${CLAUDE_DIR}. Claude Code legt seine .claude.json dann"
  echo "    AUSSERHALB des Volumes ab (ohne die Variable nach ~/.claude.json) --"
  echo "    Projektliste, Onboarding und MCP-Server waeren nach jedem Rebuild weg."
  echo "    containerEnv in devcontainer.json pruefen."
fi

echo
echo "==> Anmeldung an Codex"
if [ -r "${HOME}/.codex/auth.json" ]; then
  echo "    Zugangsdaten gefunden, die Anmeldung aus dem Volume ~/.codex gilt weiter."
else
  echo "    Noch keine Zugangsdaten in ~/.codex/auth.json."
  echo "    -> Einmal 'codex login --device-auth' aufrufen und den Code im"
  echo "       Browser des HOSTS eingeben."
fi

# Nur bei einem Node-Projekt (package.json in NODE_DIR) von Belang.
if [ -f "${NODE_DIR}/package.json" ]; then
  if ! mountpoint -q "${NODE_DIR}/node_modules"; then
    echo
    echo "==> WARNUNG: node_modules ist kein eigenes Volume"
    echo "    In ${NODE_DIR} liegt eine package.json, aber node_modules ist"
    echo "    nicht gemountet. Ein Install im Container schriebe dann in den"
    echo "    Projektordner des Hosts -- mit Linux-Binaries, die auf einem macOS-"
    echo "    oder Windows-Host nicht laufen. Den node_modules-Eintrag unter"
    echo "    \"mounts\" in devcontainer.json pruefen."
  elif [ -z "$(ls -A "${NODE_DIR}/node_modules")" ]; then
    echo
    echo "==> Abhaengigkeiten noch nicht installiert"
    echo "    Das node_modules-Volume ist leer. Einmal im Container installieren:"
    echo "      cd src/main/frontend && yarn"
    echo "    Ein Install auf dem Host ist hier unsichtbar."
  fi
fi

echo
echo "==> postCreate.sh abgeschlossen"
echo "    Die Egress-Firewall wird beim naechsten Containerstart aktiv"
echo "    (postStartCommand -> /usr/local/lib/devcontainer-firewall/init-firewall.sh)."
