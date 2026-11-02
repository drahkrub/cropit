#!/usr/bin/env bash
# .devcontainer/install-firewall.sh
#
# Legt die Firewall root-eigen nach FIREWALL_DIR: init-firewall.sh,
# refresh-allowlist.sh und allowed-domains.txt. Von dort startet sie
# postStartCommand, und nur dieses eine Skript darf "node" per sudo aufrufen
# (die Regel schreibt postCreate.sh am Ende).
#
# Warum eine Kopie und nicht die Dateien im Repo: das Repo ist fuer "node"
# schreibbar. Duerfte "node" ein Skript von dort als root starten, braeuchte
# es nur eine Zeile darin zu aendern und haette root. Dasselbe gilt fuer die
# Domainliste: liest der root-Daemon sie aus dem Repo, traegt sich jeder
# selbst frei, der die Datei editieren kann.
#
# Aufruf, immer als root:
#   - aus postCreate.sh (per sudo, solange es das noch darf),
#   - nach einer Aenderung an allowed-domains.txt vom HOST aus:
#       docker exec -u root -w <ordner mit .devcontainer> claude-<projekt> \
#         bash .devcontainer/install-firewall.sh
#     Laeuft die Firewall schon, werden neue Domains sofort freigegeben.
#     Gestrichene fallen nach ENTRY_TTL (refresh-allowlist.sh, 1 h) von selbst
#     heraus, sofort erst mit einem Neustart des Containers.
#
# Den Pfad FIREWALL_DIR gibt es dreimal: hier, in der sudo-Regel
# (postCreate.sh) und in postStartCommand (devcontainer.json).

set -euo pipefail

FIREWALL_DIR=/usr/local/lib/devcontainer-firewall
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$(id -u)" != 0 ]; then
  echo "install-firewall.sh muss als root laufen (vom Host: docker exec -u root ...)" >&2
  exit 1
fi

# root:root und fuer niemanden sonst schreibbar -- auch das Verzeichnis, sonst
# liessen sich die Dateien darin ersetzen.
install -d -m 0755 -o root -g root "${FIREWALL_DIR}"
install -m 0755 -o root -g root "${SRC_DIR}/init-firewall.sh" "${SRC_DIR}/refresh-allowlist.sh" "${FIREWALL_DIR}/"
install -m 0644 -o root -g root "${SRC_DIR}/allowed-domains.txt" "${FIREWALL_DIR}/"
echo "Firewall nach ${FIREWALL_DIR} installiert"

if ipset list -n allowed-domains >/dev/null 2>&1; then
  echo "Firewall laeuft -- gebe neue Domains sofort frei"
  bash "${FIREWALL_DIR}/refresh-allowlist.sh" --once
fi
