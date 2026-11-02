#!/usr/bin/env bash
# Claude Code Statuszeile – liest das Status-JSON von stdin, ein einziger jq-Aufruf
#
# Quelle ist .devcontainer/statusline.sh im Repo. setup-home.sh kopiert sie bei
# jedem Rebuild nach ~/.claude/statusline.sh; Aenderungen also hier machen, die
# Kopie im Volume wird ueberschrieben.
export TZ=Europe/Berlin

jq -r '
  def pct:   round | "\(.)%";
  def bar:   ([([. / 10 | floor, 0] | max), 10] | min) as $f
             | (("█" * $f) // "") + (("░" * (10 - $f)) // "");
  def money: (. * 100 | round) as $c
             | "$\($c / 100 | floor).\($c % 100 | tostring | if length < 2 then "0" + . else . end)";
  def wday:  ["So","Mo","Di","Mi","Do","Fr","Sa"][strflocaltime("%w") | tonumber];
  def reset(f): (.resets_at // empty | " (Reset \(f))") // "";

  [
    .model.display_name + ((.effort.level // empty | " (\(.))") // ""),

    # Nur in einem verknuepften Worktree gesetzt (Name des Eintrags unter
    # .git/worktrees/), im normalen Klon fehlt das Feld.
    (.workspace.git_worktree // empty | "Worktree \(.)"),

    (.context_window.used_percentage // 0 | "Context \(bar) \(pct)"),

    (.rate_limits.five_hour // empty | select(.used_percentage != null)
      | "5h \(.used_percentage | pct)" + reset(strflocaltime("%H:%M"))),

    (.rate_limits.seven_day // empty | select(.used_percentage != null)
      | "7d \(.used_percentage | pct)" + reset("\(wday) \(strflocaltime("%d.%m. %H:%M"))")),

    (.cost.total_cost_usd // empty | money)
  ] | join(" | ")
'
