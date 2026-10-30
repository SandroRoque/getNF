#!/usr/bin/env python3
"""The app's icon (D114): a caderneta with a receipt laid across it, askew. One definition here makes everything:

  android/app/src/main/res/drawable/ic_launcher_{foreground,background,monochrome}.xml   the adaptive icon
  docs/brand/icon.svg, icon-512.png                                                      Play's 512×512 icon
  docs/brand/feature-graphic.png                                                         Play's 1024×500 banner

Run it after changing a shape or a color. Needs rsvg-convert and ImageMagick (magick).
"""
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"
BRAND = ROOT / "docs/brand"
FONTS = RES / "font"

BG = "#F7F7F5"  # Calmo's light background
INK = "#22252A"

# An adaptive icon is 108×108 dp; launchers show about the middle 72 and guarantee a 66 dp circle. The whole drawing
# is scaled to fit that circle, centred.
SCALE = 0.88
SHIFT = (2, 1)


def rounded(x, y, w, h, r):
    return (f"M{x + r},{y} h{w - 2 * r} a{r},{r} 0 0 1 {r},{r} v{h - 2 * r} a{r},{r} 0 0 1 -{r},{r} "
            f"h-{w - 2 * r} a{r},{r} 0 0 1 -{r},-{r} v-{h - 2 * r} a{r},{r} 0 0 1 {r},-{r} z")


def pill(x, y, w, h):
    r = h / 2
    return f"M{x + r},{y} h{w - 2 * r} a{r},{r} 0 0 1 0,{h} h-{w - 2 * r} a{r},{r} 0 0 1 0,-{h} z"


RECEIPT_OUTLINE = "M0,0 H26 V33 L22.75,36 L19.5,33 L16.25,36 L13,33 L9.75,36 L6.5,33 L3.25,36 L0,33 Z"
RECEIPT_LINES = [pill(5, 6, 15, 2.8), pill(5, 12.5, 10, 2.8), pill(5, 19, 13, 2.8)]
RECEIPT_TOTAL = pill(5, 26, 16, 3.2)

# (translate, rotation, [(path, fill, stroke)]) in drawing order.
LAYERS = [
    ((24, 24), 0, [
        (rounded(5, 3, 44, 54, 5), "#E6E1D4", None),                              # the pages
        (rounded(0, 0, 44, 58, 6), "#3D5A80", None),                              # the cover
        ("M6,0 H9 V58 H6 A6,6 0 0 1 0,52 V6 A6,6 0 0 1 6,0 Z", "#2E4663", None),  # the spine
        (rounded(15, 12, 20, 10, 2), BG, None),                                   # the label
        ("M33,0 h5 v58 h-5 z", "#E0A23B", None),                                  # the elastic
    ]),
    ((54, 42), 10, [
        (RECEIPT_OUTLINE, "#FFFFFF", "#CFCCC3"),
        *[(p, "#AEB8C6", None) for p in RECEIPT_LINES],
        (RECEIPT_TOTAL, INK, None),
    ]),
]


def svg_group():
    out = [f'<g transform="translate({54 + SHIFT[0]} {54 + SHIFT[1]}) scale({SCALE}) translate(-54 -54)">']
    for (tx, ty), rot, paths in LAYERS:
        out.append(f'<g transform="translate({tx} {ty}) rotate({rot})">')
        for d, fill, stroke in paths:
            s = f' stroke="{stroke}" stroke-width="1.2" stroke-linejoin="round"' if stroke else ""
            out.append(f'<path d="{d}" fill="{fill}"{s}/>')
        out.append("</g>")
    out.append("</g>")
    return "\n".join(out)


def svg(view="0 0 108 108", size=108):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{view}">\n'
            f'<rect x="-50" y="-50" width="208" height="208" fill="{BG}"/>\n{svg_group()}\n</svg>\n')


def vector(body):
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Made by tools/make-icons.py (D114); edit the script, not this file. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n'
            '    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f"{body}</vector>\n")


def outer(inner):
    return (f'    <group android:pivotX="54" android:pivotY="54" android:scaleX="{SCALE}" android:scaleY="{SCALE}"\n'
            f'        android:translateX="{SHIFT[0]}" android:translateY="{SHIFT[1]}">\n{inner}    </group>\n')


def foreground():
    inner = ""
    for (tx, ty), rot, paths in LAYERS:
        inner += f'        <group android:translateX="{tx}" android:translateY="{ty}" android:rotation="{rot}">\n'
        for d, fill, stroke in paths:
            s = (f'\n                android:strokeColor="{stroke}" android:strokeWidth="1.2" android:strokeLineJoin="round"'
                 if stroke else "")
            inner += f'            <path android:fillColor="{fill}"{s}\n                android:pathData="{d}" />\n'
        inner += "        </group>\n"
    return vector(outer(inner))


def monochrome():
    # Themed icons (Android 13+) tint one shape: the receipt alone, its lines cut out, reads best at that size.
    (tx, ty), rot, _ = LAYERS[1]
    d = " ".join([RECEIPT_OUTLINE, *RECEIPT_LINES, RECEIPT_TOTAL])
    inner = (f'        <group android:translateX="{tx - 12}" android:translateY="{ty - 6}" android:rotation="{rot}"\n'
             f'            android:scaleX="1.3" android:scaleY="1.3">\n'
             f'            <path android:fillColor="#FF000000" android:fillType="evenOdd"\n'
             f'                android:pathData="{d}" />\n        </group>\n')
    return vector(outer(inner))


def background():
    return vector(f'    <path android:fillColor="{BG}" android:pathData="M0,0h108v108h-108z" />\n')


def run(*cmd):
    subprocess.run(cmd, check=True)


def main():
    (RES / "drawable/ic_launcher_foreground.xml").write_text(foreground())
    (RES / "drawable/ic_launcher_background.xml").write_text(background())
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(monochrome())

    BRAND.mkdir(parents=True, exist_ok=True)
    # Play's icon is the part a launcher shows: the middle 72 dp.
    (BRAND / "icon.svg").write_text(svg("18 18 72 72", 512))
    run("rsvg-convert", "-w", "512", "-h", "512", str(BRAND / "icon.svg"), "-o", str(BRAND / "icon-512.png"))

    # The banner: the drawing on the left, a line of text on the right. No app name: it isn't chosen yet (D105).
    art = BRAND / ".art.png"
    (BRAND / ".art.svg").write_text(svg("18 18 72 72", 460))
    run("rsvg-convert", "-w", "460", "-h", "460", str(BRAND / ".art.svg"), "-o", str(art))
    run("magick", "-size", "1024x500", f"xc:{BG}", str(art), "-geometry", "+40+20", "-composite",
        "-fill", INK, "-font", str(FONTS / "newsreader.ttf"), "-pointsize", "52",
        "-annotate", "+500+215", "Suas notas fiscais,", "-annotate", "+500+280", "seus preços.",
        "-fill", "#6F747C", "-font", str(FONTS / "figtree.ttf"), "-pointsize", "26",
        "-annotate", "+502+340", "Escaneie o QR da nota e acompanhe",
        "-annotate", "+502+376", "seus gastos e sua inflação pessoal.",
        "-alpha", "off", "-depth", "8", str(BRAND / "feature-graphic.png"))
    art.unlink()
    (BRAND / ".art.svg").unlink()


if __name__ == "__main__":
    main()
