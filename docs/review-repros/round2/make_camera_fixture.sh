#!/usr/bin/env bash
# Generate only synthetic test pixels; QR endpoint is emulator host loopback.
set -euo pipefail
cd "$(dirname "$0")"
qrencode -o /tmp/getnf-review-round2/qr.png -s 8 'http://10.0.2.2:55329/?p=42260111222333000181651090000000011123456784|2|1|1|synthetic'
magick -size 1920x1080 xc:'#808080' -fill white -draw 'rectangle 340,30 880,1050' -fill black -font /usr/share/fonts/liberation/LiberationSans-Regular.ttf -pointsize 24 \
 -annotate +370+85 'MERCADO SINTETICO - TESTE' \
 -annotate +370+145 'DOCUMENTO SEM VALOR FISCAL' \
 -annotate +370+215 '001 ARROZ 1 UN 10,00' \
 -annotate +370+275 '002 CAFE 1 UN 20,00' \
 -annotate +370+335 '003 LEITE 1 UN 3,00' \
 -annotate +370+395 'TOTAL 33,00' \
 -annotate +370+455 'DADOS INVENTADOS PARA AUDITORIA' \
 \( /tmp/getnf-review-round2/qr.png -resize 330x330 \) -geometry +440+600 -composite fixtures/camera-synthetic.png
