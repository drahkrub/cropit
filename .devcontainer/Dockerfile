# Basisimage plus einen einzigen Eingriff: den mitgelieferten Benutzer
# "vscode" in "node" umbenennen.
#
# Warum ueberhaupt: die Node-Images der Devcontainer-Familie (typescript-node,
# javascript-node) laufen als "node", und Frontend-Devcontainer bauen meist
# auf ihnen auf. Damit Mounts, Pfade und Dokumentation mit solchen Setups
# identisch lesbar sind (/home/node/...), gilt hier derselbe Name.
#
# Warum umbenennen statt anlegen: ein zweiter Benutzer neben "vscode" haette
# zwangslaeufig eine andere uid. updateRemoteUserUID hebt die uid des
# remoteUser spaeter auf die des Host-Benutzers an -- ist das zufaellig die
# 1000, kollidiert sie mit dem verbliebenen "vscode". Nach dem Umbenennen
# gibt es genau ein Nicht-Root-Konto, und die Frage stellt sich nicht.
FROM mcr.microsoft.com/devcontainers/base:noble

ARG OLD_USER=vscode
ARG NEW_USER=node

# Drei Schritte, die zusammengehoeren:
#   1. Benutzer und Gruppe umbenennen, Home mitverschieben.
#   2. Sudo-Regel nachziehen. Sie nennt den Benutzer beim Namen und passt
#      danach nicht mehr; ohne diesen Schritt scheitert jedes sudo in
#      postCreate.sh.
#   3. Absolute Home-Pfade in Punktdateien ersetzen (z.B. ZSH= in .zshrc).
#      usermod --move-home verschiebt die Dateien, nicht ihren Inhalt.
RUN set -eux; \
    usermod --login "$NEW_USER" --home "/home/$NEW_USER" --move-home "$OLD_USER"; \
    groupmod --new-name "$NEW_USER" "$OLD_USER"; \
    if [ -f "/etc/sudoers.d/$OLD_USER" ]; then \
      sed -i "s/\b$OLD_USER\b/$NEW_USER/g" "/etc/sudoers.d/$OLD_USER"; \
      mv "/etc/sudoers.d/$OLD_USER" "/etc/sudoers.d/$NEW_USER"; \
    fi; \
    grep -rl "/home/$OLD_USER" "/home/$NEW_USER" 2>/dev/null \
      | xargs -r sed -i "s|/home/$OLD_USER|/home/$NEW_USER|g" || true
