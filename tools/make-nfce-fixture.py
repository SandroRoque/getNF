#!/usr/bin/env python3
"""Make a small, sanitised test fixture from a real NFC-e details page (test-media/ stays local; see CLAUDE.md).

    tools/make-nfce-fixture.py test-media/pages/<page>.html server/test/fixtures/<name>.html --items 5

Keeps only the receipt markup (drops ASP.NET viewstate, scripts, portal chrome) and the first N items, recomputes the
totals so they stay consistent, and replaces everything that ties it to a real purchase with synthetic values: chave,
number, protocol, COO/PDV, store name/CNPJ/address (which would also reveal where the shopper lives), dates and times,
and the IBPT tax amounts. Item descriptions and prices are kept (they're generic). Review the output before committing.
"""
import argparse
import re


def chave_dv(first43: str) -> int:
    weight, total = 2, 0
    for ch in reversed(first43):
        total += int(ch) * weight
        weight = 2 if weight == 9 else weight + 1
    dv = 11 - total % 11
    return 0 if dv >= 10 else dv


FAKE_CNPJ = "11222333000181"  # the standard example CNPJ (valid check digits)
FAKE_STORE = "SUPERMERCADO EXEMPLO LTDA"
FAKE_ADDRESS = "RUA EXEMPLO, 100 , , CENTRO , CIDADE EXEMPLO , SC"
FAKE_DATE = "15/01/2026"
FAKE_TIME = "10:00:00"


def money(cents: int) -> str:
    return f"{cents // 100},{cents % 100:02d}"


def cents(s: str) -> int:
    return round(float(s.replace(".", "").replace(",", ".")) * 100)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--items", type=int, default=5)
    args = ap.parse_args()
    s = open(args.src, encoding="utf-8").read()

    start = s.index('<div id="conteudo"')
    end = s.index('class="notprint"', start)
    body = s[start : s.rindex("<div", start, end)]

    # Keep the first N items.
    rows = list(re.finditer(r'<tr[^>]*id="Item \+ (\d+)"[^>]*>[\s\S]*?</tr>\s*', body))
    for m in reversed(rows):
        if int(m.group(1)) > args.items:
            body = body[: m.start()] + body[m.end() :]
    kept = re.findall(r'<span class="valor">([^<]*)</span>', body)
    gross = sum(cents(v) for v in kept)

    # Recompute totals; keep the discount (capped) so discount handling stays covered.
    def total_line(label_re, value):
        nonlocal body
        body = re.sub(r"(<label>" + label_re + r"[^<]*</label><span[^>]*>)[^<]*(</span>)", lambda m: m.group(1) + value + m.group(2), body)

    disc_m = re.search(r"<label>Descontos[^<]*</label><span[^>]*>([^<]*)</span>", body)
    discount = min(cents(disc_m.group(1)), gross // 10) if disc_m else 0
    total_line(r"Qtd\. total de itens", str(len(kept)))
    total_line(r"Valor total", money(gross))
    total_line(r"Descontos", money(discount))
    total_line(r"Valor a pagar", money(gross - discount))
    body = re.sub(r'(<label class="tx">[^<]*</label>\s*<span[^>]*>)[^<]*(</span>)', lambda m: m.group(1) + money(gross - discount) + m.group(2), body)

    # Synthetic identifiers: UF and model/série are kept (they matter to parsing), the rest is made up.
    chave = re.search(r'class="chave"[^>]*>([\d\s]+)<', body).group(1)
    real = re.sub(r"\D", "", chave)
    fake43 = real[:2] + FAKE_DATE[8:10] + FAKE_DATE[3:5] + FAKE_CNPJ + real[20:25] + "000000001" + real[34] + "12345678"
    fake = fake43 + str(chave_dv(fake43))
    body = body.replace(chave, " ".join(fake[i : i + 4] for i in range(0, 44, 4)))

    # Store (and with it, the shopper's area).
    body = re.sub(r'(<div[^>]*class="txtTopo"[^>]*>)[^<]*(</div>)', lambda m: m.group(1) + FAKE_STORE + m.group(2), body)
    body = re.sub(r"CNPJ:\s*[\d./-]+", "CNPJ: 11.222.333/0001-81", body)
    body = re.sub(r'(class="txtTopo"[^>]*>[^<]*</div>\s*<div[^>]*class="text"[^>]*>[^<]*</div>\s*<div[^>]*class="text"[^>]*>)[^<]*(</div>)',
                  lambda m: m.group(1) + FAKE_ADDRESS + m.group(2), body)
    # When it happened.
    body = re.sub(r"\b\d{2}/\d{2}/\d{4}\b", FAKE_DATE, body)
    body = re.sub(r"\b\d{2}:\d{2}:\d{2}\b", FAKE_TIME, body)
    body = re.sub(r"(FEDERAL R\$ )[\d.,]+( ESTADUAL R\$ )[\d.,]+( MUNICIPAL R\$ )[\d.,]+", r"\g<1>1,23\g<2>2,34\g<3>0,00", body)
    body = re.sub(r"(IBPTEMPRESOMETRO\.COM\.BR )\w+", r"\g<1>000000", body)
    body = re.sub(r"(Número: </strong>)\d+", r"\g<1>1", body)
    body = re.sub(r"(Protocolo de Autorização: </strong>)\d+", r"\g<1>000000000000000", body)
    body = re.sub(r"COO: \d+, PDV: \d+", "COO: 1, PDV: 1", body)

    with open(args.dst, "w", encoding="utf-8") as f:
        f.write("<!-- Sanitised fixture made by tools/make-nfce-fixture.py; synthetic chave/number/protocol. -->\n")
        f.write("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>NFCe - Detalhes</title></head><body>\n")
        f.write(body)
        f.write("\n</body></html>\n")
    print(f"{args.dst}: {len(kept)} items, gross {money(gross)}, discount {money(discount)}, chave {fake}")


if __name__ == "__main__":
    main()
