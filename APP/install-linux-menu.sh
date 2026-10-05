#!/bin/sh
# Linux only: adds "Endpoint Checker" to the applications menu. Run once from this folder:
#   sh install-linux-menu.sh
# (Re-run it if you move this folder.)
dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HOME/.local/share/applications"
cat > "$HOME/.local/share/applications/endpoint-checker.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Endpoint Checker
Comment=Small Postman-like API tester (opens in your browser)
Exec=java -jar "$dir/endpoint-checker.jar"
Path=$dir
Terminal=false
Categories=Development;Network;
DESKTOP
echo "Added to the applications menu (log out and in again if it does not show up)."
