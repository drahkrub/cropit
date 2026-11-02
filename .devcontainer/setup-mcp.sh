#!/usr/bin/env bash
# .devcontainer/setup-mcp.sh
# Registriert die benoetigten MCP-Server bei Claude Code.
# Idempotent: laeuft bei jedem Rebuild erneut, ohne bei bereits vorhandenen
# Servern (persistiert im ~/.claude-Volume) abzubrechen.
#
# Wichtig: Jede hier eingetragene Domain muss auch in allowed-domains.txt
# stehen, sonst ist der Server nach dem naechsten Containerstart nicht
# erreichbar ("No route to host").

set -uo pipefail  # bewusst kein -e: "mcp add" auf existierenden Server soll nicht abbrechen

add_mcp_server() {
  local name="$1"
  local url="$2"

  if claude mcp list 2>/dev/null | grep -q "^${name}"; then
    echo "  - ${name}: bereits registriert, ueberspringe"
  else
    echo "  - ${name}: registriere..."
    claude mcp add --transport http "${name}" "${url}"
  fi
}

echo "Richte MCP-Server ein:"

# Nuxt UI MCP-Server: Komponenten-API, Props, Slots, Beispiele.
# Relevant fuer den Vue/TS/Nuxt-UI-Anteil des Projekts.
add_mcp_server "nuxt-ui" "https://ui.nuxt.com/mcp"

# Nuxt-Framework-Doku. Nur einkommentieren, wenn wirklich gebraucht -- bei
# einer reinen Vite-SPA mit Nuxt UI als Komponentenbibliothek ist sie es meist
# nicht, und jeder MCP-Server kostet Kontext.
# add_mcp_server "nuxt" "https://nuxt.com/mcp"

echo ""
echo "Aktuell registrierte MCP-Server:"
claude mcp list
