#!/usr/bin/env bash
# .devcontainer/setup-home.sh
# Richtet Einstellungen im Home ein: git, die Statuszeile von Claude Code und
# zuletzt die persoenliche Datei ~/.claude/personal.sh, falls es sie gibt.
#
# Laeuft bei jedem Rebuild (aus postCreate.sh), darf aber auch von Hand
# erneut laufen, etwa nach einer Aenderung an personal.sh:
#   bash .devcontainer/setup-home.sh
# Alles hier ist idempotent.
#
# Warum ueberhaupt bei jedem Rebuild: ~/.gitconfig und ~/.bash_aliases liegen
# im Container-Dateisystem, nicht in einem Volume (warum nicht das ganze Home
# ein Volume ist: siehe devcontainer.json bei ~/.cache). Nach dem Rebuild sind
# sie weg. ~/.claude dagegen ist ein Volume, was dort liegt, bleibt.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLAUDE_DIR="${CLAUDE_CONFIG_DIR:-${HOME}/.claude}"

echo "==> git: delta als Pager, vim als Editor"
# Fuer alle, die den Container benutzen. Die Identitaet (user.name/email)
# gehoert NICHT hierher, sondern in personal.sh: diese Datei ist eingecheckt,
# und jeder andere Nutzer des Containers committete sonst unter fremdem Namen.
#
# Pager nur, wenn delta wirklich installiert ist (postCreate.sh ueberspringt
# es auf unbekannten Architekturen). Mit einem fehlenden Pager bricht jedes
# "git log" und "git diff" im Terminal ab, statt ohne Pager weiterzulaufen.
#
# Claude Code ruft git ohne Terminal auf, dann zieht git keinen Pager hinzu.
# An dessen Ausgaben aendert sich also nichts.
if command -v delta >/dev/null 2>&1; then
  git config --global core.pager delta
  # Auch "git add -p" zeigt die Hunks dann in delta-Farben. --color-only ist
  # Pflicht: git verlangt vom Filter genau so viele Zeilen, wie es hineingibt,
  # und bricht sonst mit "mismatched output from interactive.diffFilter" ab.
  git config --global interactive.diffFilter 'delta --color-only'
else
  echo "    (delta nicht gefunden -- core.pager bleibt ungesetzt)"
fi
# Doppelt zu EDITOR=vim aus containerEnv, aber nicht ueberfluessig: core.editor
# schlaegt VISUAL, falls eine Umgebung das setzt.
git config --global core.editor vim

echo "==> Claude Code: Statuszeile"
# Immer kopieren, nicht nur beim ersten Mal: die Quelle im Repo ist
# massgeblich, sonst bliebe nach einer Aenderung dort still die alte Kopie
# im Volume stehen.
install -m 0755 "${SCRIPT_DIR}/statusline.sh" "${CLAUDE_DIR}/statusline.sh"

# In settings.json nur eintragen, wenn dort noch KEINE Statuszeile steht. Wer
# eine eigene eingetragen hat, behaelt sie; die Datei liegt im Volume und
# gehoert dem Nutzer. Wie Claude Code selbst: in eine Hilfsdatei schreiben,
# dann umbenennen, damit kein Leser eine halb geschriebene Datei sieht.
SETTINGS="${CLAUDE_DIR}/settings.json"
STATUSLINE_CMD='bash ~/.claude/statusline.sh'
[ -s "${SETTINGS}" ] || echo '{}' > "${SETTINGS}"
current_cmd="$(jq -r '.statusLine.command // empty' "${SETTINGS}" 2>/dev/null)" || {
  echo "    WARNUNG: ${SETTINGS} ist kein gueltiges JSON -- Statuszeile nicht eingetragen"
  current_cmd="(ungueltig)"
}
if [ -z "${current_cmd}" ]; then
  tmp="${SETTINGS}.tmp.$$"
  jq --arg cmd "${STATUSLINE_CMD}" '.statusLine = {type: "command", command: $cmd}' \
    "${SETTINGS}" > "${tmp}"
  mv "${tmp}" "${SETTINGS}"
  echo "    in ${SETTINGS} eingetragen"
elif [ "${current_cmd}" = "${STATUSLINE_CMD}" ]; then
  echo "    bereits eingetragen"
elif [ "${current_cmd}" != "(ungueltig)" ]; then
  echo "    ${SETTINGS} hat schon eine eigene Statuszeile ('${current_cmd}') -- bleibt"
fi

echo "==> Persoenliche Einstellungen"
# Persoenliches (git-Identitaet, Aliase) steht in einer Datei im Home-Volume,
# nicht im Repo: so ueberlebt sie Rebuilds und den Wechsel zwischen Klon und
# Worktree desselben Projekts, und kein anderer Nutzer erbt sie.
#
# Eigener Prozess statt "source": die Datei kann diesem Skript keine Variablen
# oder Optionen verstellen. Ein Fehler darin bricht den Rebuild nicht ab; der
# Container ist ohne sie voll benutzbar.
PERSONAL="${CLAUDE_DIR}/personal.sh"
if [ -f "${PERSONAL}" ]; then
  if bash "${PERSONAL}"; then
    echo "    ${PERSONAL} ausgefuehrt"
  else
    echo "    WARNUNG: ${PERSONAL} ist mit Fehler ausgestiegen -- bitte pruefen"
  fi
else
  echo "    Keine ${PERSONAL}. Ohne sie hat git keine Identitaet, und der erste"
  echo "    Commit scheitert. Vorlage:"
  echo "      cp .devcontainer/personal.sh.example ${PERSONAL}"
  echo "    anpassen, dann: bash .devcontainer/setup-home.sh"
fi
