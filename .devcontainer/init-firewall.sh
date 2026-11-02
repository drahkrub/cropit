#!/usr/bin/env bash
# .devcontainer/init-firewall.sh
#
# Default-deny-Egress-Firewall fuer den Devcontainer: erlaubt ist nur, was in
# allowed-domains.txt steht bzw. ueber die GitHub-Meta-API aufgeloest wird,
# alles andere wird abgelehnt. Zweck ist nicht Perfektion, sondern eine
# schmale Reichweite fuer einen Agenten, der auch fremde Inhalte liest
# (Webseiten, Issue-Kommentare, MCP-Antworten, Paket-Readmes).
#
# Laeuft als postStartCommand bei JEDEM Containerstart, weil iptables-Regeln
# einen Neustart nicht ueberleben. Und zwar NICHT aus dem Repo, sondern als
# root-eigene Kopie aus /usr/local/lib/devcontainer-firewall/ (siehe
# install-firewall.sh): es ist das einzige, was "node" per sudo darf.
#
# Steht die Firewall schon, tut ein zweiter Aufruf nichts (siehe "Schon
# aktiv?" unten). Neu aufgebaut wird sie nur durch einen Neustart des
# Containers.
#
# Braucht --cap-add=NET_ADMIN und --cap-add=NET_RAW (siehe devcontainer.json)
# sowie iptables, ipset, dig, aggregate, jq und curl (installiert postCreate.sh).
#
# Dieses Skript stammt NICHT aus der Claude-Code-Devcontainer-Feature und muss
# deshalb auch nicht zur Laufzeit gepatcht werden. Die vier Korrekturen, die
# Setups mit der Feature-Firewall als Patch A-D nachtraeglich einspielen
# muessen, sind hier von vornherein eingebaut und unten benannt.
#
# Aufteilung der Dateien:
#   allowed-domains.txt    die Hostnamen, gemeinsam genutzt
#   refresh-allowlist.sh   loest sie auf und haelt das ipset aktuell
#   init-firewall.sh       diese Datei: Regelwerk, feste Netze, Selbsttest
#   install-firewall.sh    legt die drei root-eigen ab (bleibt im Repo)

set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REFRESH_SCRIPT="${SCRIPT_DIR}/refresh-allowlist.sh"
REFRESH_PIDFILE="/run/allowlist-refresh.pid"
REFRESH_LOG="/var/log/allowlist-refresh.log"

# Ohne Auffrischungs-Daemon gilt die Momentaufnahme vom Containerstart.
# sudo verwirft die Umgebung, ueber postStartCommand ist die Variable also
# nicht zu setzen. Den laufenden Daemon beendet root (vom Host per
# docker exec -u root) mit: kill "$(cat /run/allowlist-refresh.pid)"
REFRESH_ALLOWLIST="${REFRESH_ALLOWLIST:-1}"

# --- Schon aktiv? ---------------------------------------------------------
# Ein zweiter Lauf im laufenden Container ginge schief: "iptables -F" unten
# leert nur die Regeln, die Default-Policy DROP bleibt stehen. Dann kaeme der
# Abruf der GitHub-Netze nicht mehr durch, das Skript braeche ab und liesse
# einen Container ohne Netz zurueck (nur DNS und Loopback), auch fuer Claude
# Code selbst. Erst die Policy auf ACCEPT zu setzen hiesse dagegen, die
# Firewall fuer einige Sekunden ganz zu oeffnen -- auf Zuruf von "node", das
# dieses Skript per sudo starten darf. Daher: nichts tun.
#
# Ein Neustart des Containers verwirft alle Regeln, danach greift das hier nicht.
#
# Bewusst voruebergehend oeffnen und wieder schliessen kann nur root, also vom
# HOST aus. Oeffnen setzt die Policy auf ACCEPT, damit greift diese Sperre
# nicht mehr, und leert OUTPUT -- sonst lehnte die REJECT-Regel am Ende der
# Kette weiter alles ab. Ein erneuter Aufruf baut die Firewall dann komplett
# neu auf:
#   docker exec -u root claude-<projekt> sh -c 'iptables -P OUTPUT ACCEPT; iptables -F OUTPUT'
#   docker exec -u root claude-<projekt> /usr/local/lib/devcontainer-firewall/init-firewall.sh
# Solange sie offen ist, hat auch Claude Code freies Netz.
if iptables -S OUTPUT 2>/dev/null | grep -qx -- '-P OUTPUT DROP'; then
  echo "Firewall ist bereits aktiv -- nichts zu tun. Neu aufbauen: Container neu starten."
  exit 0
fi

# --- Alten Refresher beenden, bevor das ipset verschwindet ----------------
# Sonst schreibt ein Daemon aus dem vorigen Lauf gegen ein Set, das es gleich
# nicht mehr gibt, und protokolliert im Sekundentakt Fehlschlaege.
if [ -f "$REFRESH_PIDFILE" ]; then
  kill "$(cat "$REFRESH_PIDFILE")" 2>/dev/null || true
  rm -f "$REFRESH_PIDFILE"
fi

# --- Aufraeumen -----------------------------------------------------------

iptables -F
iptables -X
iptables -t nat -F
iptables -t nat -X
iptables -t mangle -F
iptables -t mangle -X
ipset destroy allowed-domains 2>/dev/null || true

# --- Grundregeln, bevor die Default-Policy auf DROP geht ------------------

# DNS nur zu den tatsaechlich konfigurierten Resolvern (Patch C, Bug
# github.com/anthropics/claude-code/issues/36907).
# Das Original erlaubt Port 53 zu JEDEM Ziel -- damit laesst sich die
# IP-Allowlist per DNS-Tunneling umgehen, was vor allem in Kombination mit
# --dangerously-skip-permissions zaehlt.
#
# Der Resolver liegt NICHT immer auf 127.0.0.11: bei durchgereichten
# Host-Resolvern (Kommentar "Based on host file" in /etc/resolv.conf) sind es
# die Nameserver des Hosts. Deshalb zur Laufzeit auslesen statt annehmen.
DNS_RESOLVERS=$(awk '/^nameserver/ {print $2}' /etc/resolv.conf 2>/dev/null | sort -u || true)
if [ -z "$DNS_RESOLVERS" ]; then
  echo "WARN: Kein nameserver in /etc/resolv.conf gefunden, erlaube DNS unbeschraenkt"
  iptables -A OUTPUT -p udp --dport 53 -j ACCEPT
  iptables -A INPUT -p udp --sport 53 -j ACCEPT
else
  for ns in $DNS_RESOLVERS; do
    echo "Erlaube DNS zu Resolver: $ns"
    iptables -A OUTPUT -p udp --dport 53 -d "$ns" -j ACCEPT
    iptables -A OUTPUT -p tcp --dport 53 -d "$ns" -j ACCEPT
    iptables -A INPUT -p udp --sport 53 -s "$ns" -j ACCEPT
    iptables -A INPUT -p tcp --sport 53 -s "$ns" -j ACCEPT
  done
fi

# Kein SSH: Port 22 zu beliebigen Zielen waere ein Allzweck-Tunnel
# (ssh -D/-L, scp) und damit ein Weg an der IP-Allowlist vorbei. Git laeuft
# ueber HTTPS in die GitHub-Netze, die ohnehin freigegeben sind.

# Loopback
iptables -A INPUT -i lo -j ACCEPT
iptables -A OUTPUT -o lo -j ACCEPT

# --- Allowlist als ipset aufbauen ----------------------------------------

# "timeout 0" schaltet die Ablauf-Extension ein und setzt als Standard
# "laeuft nie ab". Alles, was hier direkt eingetragen wird (die GitHub-Netze),
# ist damit dauerhaft. refresh-allowlist.sh traegt die
# aufgeloesten Hostnamen dagegen MIT Ablaufzeit ein und frischt sie zyklisch
# auf: lebende Adressen bleiben, verschwundene fallen von selbst heraus.
# Ohne diese Extension wuerde das Set bei jeder IP-Rotation monoton wachsen.
ipset create allowed-domains hash:net timeout 0

echo "Hole GitHub-IP-Bereiche..."
gh_ranges=$(curl -s --connect-timeout 10 https://api.github.com/meta || true)
if [ -z "$gh_ranges" ] || ! echo "$gh_ranges" | jq -e '.web and .api and .git' >/dev/null 2>&1; then
  echo "ERROR: GitHub-IP-Bereiche nicht abrufbar"
  exit 1
fi
while read -r cidr; do
  [[ "$cidr" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}/[0-9]{1,2}$ ]] || continue
  ipset add allowed-domains "$cidr" 2>/dev/null || true
done < <(echo "$gh_ranges" | jq -r '(.web + .api + .git)[]' | aggregate -q)

# Hostnamen aus allowed-domains.txt aufloesen. Bewusst delegiert, damit es die
# Aufloese-Logik (IPv4-Filter = Patch D, Fail-Soft bei nicht aufloesbaren
# Domains = Patch A) nur EINMAL im Projekt gibt und Erstbefuellung und
# Auffrischung nicht auseinanderlaufen koennen.
echo "Loese Hostnamen aus allowed-domains.txt auf..."
bash "$REFRESH_SCRIPT" --once

# --- Host-Netz -----------------------------------------------------------

# Das /24 des Default-Gateways freigeben. Darueber laufen host.docker.internal
# und damit Dienste, die auf dem Host lauschen (Datenbank, zweites Backend),
# ausserdem der Kanal zur IDE.
HOST_IP=$(ip route | grep default | cut -d" " -f3)
if [ -z "$HOST_IP" ]; then
  echo "ERROR: Host-IP nicht ermittelbar"
  exit 1
fi
HOST_NETWORK=$(echo "$HOST_IP" | sed "s/\.[0-9]*$/.0\/24/")
echo "Host-Netz erkannt: $HOST_NETWORK"
iptables -A INPUT -s "$HOST_NETWORK" -j ACCEPT
iptables -A OUTPUT -d "$HOST_NETWORK" -j ACCEPT

# --- Default-Policy auf DROP, dann gezielt oeffnen -----------------------

iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT DROP

iptables -A INPUT  -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m set --match-set allowed-domains dst -j ACCEPT

# Alles Uebrige ABLEHNEN statt nur verwerfen. Die Policy DROP allein bleibt
# stumm: das Programm erfaehrt nichts und wartet bis zu seinem eigenen
# Timeout, bei curl ohne Timeout ewig. Am 2026-10-06 so dreimal minutenlang
# gehangen, ohne Fehlermeldung: VS-Code-Extensions, ein zusaetzliches
# Maven-Repository, nvm an iojs.org. Mit REJECT scheitert die Verbindung
# sofort mit "No route to host" -- das heisst hier: Firewall, Domain fehlt in
# allowed-domains.txt. Anthropics Referenz-Firewall hat dieselbe Regel.
#
# Muss die LETZTE Regel der Kette sein, alles Erlaubte steht davor. Die
# ICMP-Antwort, die REJECT erzeugt, laeuft ueber Loopback und kommt durch die
# lo-Regeln oben herein.
#
# Preis: wechselt ein freigegebener Host auf eine Adresse, die
# refresh-allowlist.sh noch nicht nachgetragen hat (bis zu 60 s), scheitert der
# Abruf sofort, statt mit Glueck verspaetet doch noch durchzukommen. Gemessen
# am 2026-10-06: 26 neue Adressen in 75 Minuten, fast alle in den ersten 12,
# keine bei npm, Maven Central, Anthropic oder GitHub.
#
# Die Policy DROP bleibt trotzdem stehen: der Waechter oben erkennt an ihr die
# laufende Firewall, und sie greift, falls jemand diese Regel entfernt.
iptables -A OUTPUT -j REJECT --reject-with icmp-admin-prohibited

# IPv6 komplett dicht. Das ipset oben kennt nur IPv4 (dig +short A), eine
# IPv6-Route am Container waere also ein Weg an der Allowlist vorbei -- still,
# weil getaddrinfo IPv6 bevorzugt. Dockers Default-Bridge hat kein IPv6, das
# ist hier Vorsorge fuer den Fall, dass doch eins dranhaengt.
# Ausgenommen bleibt ::1: sonst bricht alles, was sich lokal auf localhost
# verbindet (Vite-Dev-Server, Backend-Proxy, MCP ueber stdio-Sockets).
if command -v ip6tables >/dev/null 2>&1 && ip6tables -L >/dev/null 2>&1; then
  ip6tables -F
  ip6tables -A INPUT  -i lo -j ACCEPT
  ip6tables -A OUTPUT -o lo -j ACCEPT
  ip6tables -P INPUT DROP
  ip6tables -P FORWARD DROP
  ip6tables -P OUTPUT DROP
  echo "  IPv6: Default-Policy DROP (nur ::1 offen)"
else
  echo "  IPv6: ip6tables nicht verfuegbar, uebersprungen"
fi

# --- Selbsttest ----------------------------------------------------------
# Beide Richtungen pruefen: eine Firewall, die alles blockt, ist genauso
# kaputt wie eine, die nichts blockt.

echo "Firewall aufgebaut, pruefe..."
if curl --connect-timeout 5 -s https://example.com >/dev/null 2>&1; then
  echo "ERROR: example.com ist erreichbar -- die Firewall greift nicht"
  exit 1
fi
echo "  OK: example.com ist blockiert"

if ! curl --connect-timeout 5 -s https://api.github.com/zen >/dev/null 2>&1; then
  echo "ERROR: api.github.com ist NICHT erreichbar -- die Allowlist greift nicht"
  exit 1
fi
echo "  OK: api.github.com ist erreichbar"

# --- Auffrischung im Hintergrund starten ---------------------------------

if [ "$REFRESH_ALLOWLIST" = "1" ]; then
  # setsid loest den Prozess von dieser Shell: postStartCommand kehrt sofort
  # zurueck, der Daemon laeuft weiter. Ohne setsid nimmt ihn das Ende der
  # postStart-Sitzung mit.
  #
  # Das Log waechst kaum, weil refresh-allowlist.sh nur bei tatsaechlichen
  # Aenderungen schreibt; bei jedem Containerstart faengt es ausserdem neu an.
  : > "$REFRESH_LOG"
  setsid bash "$REFRESH_SCRIPT" --daemon >> "$REFRESH_LOG" 2>&1 &
  echo $! > "$REFRESH_PIDFILE"
  echo "Allowlist-Refresher laeuft (PID $(cat "$REFRESH_PIDFILE"), Log: ${REFRESH_LOG})"
else
  echo "Allowlist-Refresher deaktiviert (REFRESH_ALLOWLIST=0):"
  echo "  Die Freigaben sind eine Momentaufnahme von jetzt. Rotieren die IPs"
  echo "  eines Endpunkts, scheitern dessen Requests danach (No route to host)."
fi

echo "Firewall aktiv."
