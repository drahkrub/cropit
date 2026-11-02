#!/usr/bin/env bash
# .devcontainer/refresh-allowlist.sh
#
# Hält das ipset "allowed-domains" aktuell, ohne es je zu leeren.
#
# Warum es das braucht: init-firewall.sh löst die Hostnamen EINMAL beim
# Containerstart auf. Das ist eine Momentaufnahme. Endpunkte hinter
# Loadbalancern und CDNs wechseln ihre IPs -- registry.npmjs.org etwa liefert
# ein gutes Dutzend Cloudflare-Adressen, und welche davon in der Antwort
# stehen, ändert sich. Zeigt ein frischer DNS-Antwortsatz auf eine IP, die
# nicht im ipset steht, lehnt die Firewall die Verbindung ab: "No route to
# host", während alles andere weiterläuft. (Bis 2026-10-06 verwarf sie solche
# Pakete nur still, dann gab es statt der Meldung rund zwei Minuten
# SYN-Retransmits und einen nichtssagenden Timeout.) Dieses Skript trägt die
# neue Adresse beim nächsten Durchlauf nach, danach klappt der Abruf wieder.
#
# api.anthropic.com ist derzeit unkritisch (ein A-Record, TTL 176s), das ist
# aber nichts, worauf man ein Setup baut.
#
# Vorgehen: nur HINZUFÜGEN, nie entfernen. Bestehende Verbindungen reißen
# dadurch nie ab. Gegen unbegrenztes Wachstum sorgt nicht dieses Skript,
# sondern die Ablaufzeit der Einträge -- das ipset wird mit der
# timeout-Extension angelegt (init-firewall.sh), aufgelöste IPs bekommen
# ENTRY_TTL Sekunden. Wer noch im DNS steht, wird vor Ablauf wieder
# aufgefrischt; wer verschwunden ist, fällt von selbst heraus. Feste Einträge
# (GitHub-Netze, Host-Netz) werden dort mit "timeout 0" ergänzt und sind davon
# nicht betroffen.
#
# Aufrufe, beide als root und aus der root-eigenen Kopie unter
# /usr/local/lib/devcontainer-firewall/ (install-firewall.sh legt sie ab und
# liest dabei die Domainliste aus dem Repo neu ein):
#   refresh-allowlist.sh --once     einmal auffrischen (das tut install-firewall.sh
#                                   nach dem Editieren der Domainliste)
#   refresh-allowlist.sh --daemon   Endlosschleife (startet init-firewall.sh
#                                   im Hintergrund)
#
# Braucht root (ipset) und ein bereits existierendes ipset.

set -uo pipefail  # bewusst kein -e: ein einzelner DNS-Aussetzer darf den Daemon nicht beenden

IPSET_NAME="${ALLOWLIST_IPSET:-allowed-domains}"
DOMAIN_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/allowed-domains.txt"

# Ablaufzeit aufgelöster IPs. Deutlich größer als das Intervall, damit ein
# einzelner fehlgeschlagener Durchlauf (DNS kurz weg) keinen Eintrag verliert.
ENTRY_TTL="${ALLOWLIST_ENTRY_TTL:-3600}"
# Wie oft der Daemon nachschaut. 60s liegt unter den kürzesten TTLs der
# Allowlist und weit unter ENTRY_TTL; ein Durchlauf sind gut zwanzig
# DNS-Anfragen, die Kosten sind vernachlässigbar.
INTERVAL="${ALLOWLIST_REFRESH_INTERVAL:-60}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

read_domains() {
  # Kommentare (auch am Zeilenende) und Leerzeichen entfernen, Leerzeilen weg.
  sed -e 's/#.*//' -e 's/[[:space:]]//g' "$DOMAIN_FILE" | grep -v '^$'
}

refresh_once() {
  local added=0 domain ips ip

  if ! ipset list -n "$IPSET_NAME" >/dev/null 2>&1; then
    log "ipset '${IPSET_NAME}' existiert nicht -- läuft die Firewall? Überspringe."
    return 1
  fi

  while read -r domain; do
    # dig-Ausgabe auf reine IPv4-Zeilen filtern: hinter CNAME-Ketten liefert
    # "dig +short A" auch Hostnamen zurück.
    ips=$(dig +short A "$domain" 2>/dev/null \
          | grep -E '^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$')

    # Nicht auflösbare Domains sind kein Fehler: statsig.anthropic.com etwa hat
    # keine öffentliche DNS-Auflösung und würde sonst jeden Start blockieren.
    [ -z "$ips" ] && continue

    while read -r ip; do
      # "-exist" macht das erneute Hinzufügen still UND setzt die Ablaufzeit
      # zurück -- genau das hält lebende Einträge am Leben.
      if ! ipset test "$IPSET_NAME" "$ip" >/dev/null 2>&1; then
        log "  neu: ${ip} (${domain})"
        added=$((added + 1))
      fi
      ipset -exist add "$IPSET_NAME" "$ip" timeout "$ENTRY_TTL" 2>/dev/null
    done <<< "$ips"
  done <<< "$(read_domains)"

  # Bewusst nur bei Änderungen laut: ein Daemon, der im Minutentakt "alles ok"
  # protokolliert, macht das Log unlesbar und verdeckt genau die Zeile, die
  # man später sucht.
  [ "$added" -gt 0 ] && log "${added} neue Adresse(n) freigegeben"
  return 0
}

case "${1:---once}" in
  --once)
    refresh_once
    ;;
  --daemon)
    log "Allowlist-Refresher gestartet (Intervall ${INTERVAL}s, Eintragsdauer ${ENTRY_TTL}s)"
    while true; do
      refresh_once
      sleep "$INTERVAL"
    done
    ;;
  *)
    echo "Aufruf: $0 [--once|--daemon]" >&2
    exit 2
    ;;
esac
