#!/usr/bin/env bash
set -e

echo "==> Building OXT..."
./gradlew oxt

open_docs=""
if pgrep -f soffice.bin > /dev/null 2>&1; then
    open_docs=$(pgrep -a -f soffice.bin \
        | grep -oP '(/\S+\.(?:odt|ods|odp|doc|docx|xls|xlsx|ppt|pptx))' \
        | tr '\n' ' ' || true)
    echo "==> Closing LibreOffice (open: ${open_docs:-none})..."
    pkill -f soffice.bin
    while pgrep -f soffice.bin > /dev/null 2>&1; do
        sleep 0.5
    done
    echo "    LibreOffice closed."
fi

echo "==> Removing old extension..."
unopkg remove org.msc.liberekollab 2>/dev/null || true

echo "==> Installing new extension..."
unopkg add build/oxt/LibereKollab-1.0-SNAPSHOT.oxt

if [ -n "$open_docs" ]; then
    echo "==> Reopening LibreOffice with previous documents..."
    soffice $open_docs > /dev/null 2>&1 &
    echo "    Don't forget: Extras > Optionen > Internet > MCP Server > Start server"
else
    echo ""
    echo "Done. Start LibreOffice and enable the server via:"
    echo "  Extras > Optionen > Internet > MCP Server > Start server"
fi
