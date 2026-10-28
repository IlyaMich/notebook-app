"""Build the deterministic FMT-03 synthetic mixed-content golden notebook.

Run from repository root: py -3 shared/fixtures/build_golden.py
Only Python standard library is needed for generation.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import uuid
import zipfile
import zlib

FIXTURE_DIR = Path(__file__).resolve().parent
OUTPUT = FIXTURE_DIR / "valid" / "mixed_pages_v1.notebook"
NAMESPACE = uuid.UUID("b1f9e538-fb97-4c19-8307-8dde5b4f28bd")


def identifier(label: str) -> str:
    return str(uuid.uuid5(NAMESPACE, label))


def _png_chunk(kind: bytes, data: bytes) -> bytes:
    payload = kind + data
    return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xffffffff)


def make_png() -> bytes:
    """A real 3-by-2 RGB PNG with synthetic colored stripes."""
    width, height = 3, 2
    rows = [
        bytes([0]) + bytes([220, 60, 75,  75, 160, 225,  255, 200, 70]),
        bytes([0]) + bytes([80, 180, 110,  150, 90, 200,  235, 235, 235]),
    ]
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"".join(rows)
    # Deflate stored-block encoding avoids platform-specific zlib compression output.
    stored_deflate = (b"\x01" + struct.pack("<H", len(raw))
                      + struct.pack("<H", 0xffff - len(raw)) + raw)
    zlib_stream = b"\x78\x01" + stored_deflate + struct.pack(">I", zlib.adler32(raw) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", header)
            + _png_chunk(b"IDAT", zlib_stream)
            + _png_chunk(b"IEND", b""))


def make_pdf() -> bytes:
    """A minimal, valid, deterministic two-page PDF with no external assets."""
    page1 = b"q 0.88 0.92 1 rg 20 20 260 260 re f Q\n"
    page2 = b"q 0.94 0.94 0.94 rg 30 30 552 732 re f Q\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 4 0 R /Resources << >> >>",
        b"<< /Length %d >>\nstream\n" % len(page1) + page1 + b"endstream",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [0 0 612 792] /Contents 6 0 R /Resources << >> >>",
        b"<< /Length %d >>\nstream\n" % len(page2) + page2 + b"endstream",
    ]
    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, payload in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{index} 0 obj\n".encode("ascii") + payload + b"\nendobj\n")
    start_xref = len(result)
    result.extend(f"xref\n0 {len(objects)+1}\n".encode("ascii"))
    result.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    result.extend((f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\n"
                   f"startxref\n{start_xref}\n%%EOF\n").encode("ascii"))
    return bytes(result)


def element(name: str, kind: str, payload: dict, transform=None, locked=False) -> dict:
    return {"id": identifier(name), "type": kind, "transform": transform or [1, 0, 0, 1, 0, 0],
            "locked": locked, "payload": payload}


def page(name: str, width: float, height: float, background: dict, elements: list[dict]) -> dict:
    return {"id": identifier(name), "size": {"width": width, "height": height},
            "background": background, "elements": elements}


def make_documents() -> tuple[dict, dict[str, dict], dict[str, bytes]]:
    png = make_png()
    pdf = make_pdf()
    png_hash, pdf_hash = hashlib.sha256(png).hexdigest(), hashlib.sha256(pdf).hexdigest()
    image_asset_id, pdf_asset_id = identifier("asset:png"), identifier("asset:pdf")

    pen = element("element:pen", "stroke", {
        "encoding": "sampledPointsV1",
        "brush": {"kind": "pen", "width": 2.5, "color": "#2255AA", "opacity": 1.0},
        "points": [
            {"x": 80, "y": 110, "tMs": 0, "pressure": 0.45},
            {"x": 99, "y": 100, "tMs": 18, "pressure": 0.65, "tiltRad": 0.2},
            {"x": 119, "y": 113, "tMs": 36, "pressure": 0.52},
        ],
    })
    arrow = element("element:arrow", "shape", {
        "kind": "arrow", "geometry": {"x1": 170, "y1": 160, "x2": 250, "y2": 110},
        "stroke": {"color": "#008F70", "width": 3, "opacity": 1},
        "fill": None, "arrowHead": {"length": 12, "angleDeg": 30},
    }, transform=[1, 0, 0, 1, 5, 10])
    text = element("element:text", "text", {
        "text": "F = ma • synthetic fixture",
        "frame": {"x": 80, "y": 190, "width": 260, "height": 42},
        "style": {"fontFamily": "sans", "fontSize": 20, "color": "#202020",
                  "bold": True, "italic": False, "underline": False, "alignment": "left"},
    })
    first = page("page:ruled-a4", 595.276, 841.890,
                 {"type": "ruled", "color": "#FFFFFF", "patternColor": "#D8E7F7", "spacing": 24},
                 [pen, arrow, text])

    image = element("element:image", "image", {
        "assetId": image_asset_id, "frame": {"x": 275, "y": 150, "width": 130, "height": 86.67},
        "opacity": 0.9, "crop": {"x": 0.1, "y": 0, "width": 0.8, "height": 1},
    })
    rectangle = element("element:rectangle", "shape", {
        "kind": "rectangle", "geometry": {"x": 80, "y": 200, "width": 180, "height": 90},
        "stroke": {"color": "#C03030", "width": 2, "opacity": 1},
        "fill": {"color": "#F9E2E2", "opacity": 0.5},
    })
    second = page("page:grid-letter-landscape", 792, 612,
                  {"type": "grid", "color": "#FFFFFF", "patternColor": "#DFE6EF", "spacing": 18},
                  [image, rectangle])

    highlighter = element("element:highlighter", "stroke", {
        "encoding": "sampledPointsV1",
        "brush": {"kind": "highlighter", "width": 12, "color": "#FFCC33", "opacity": 0.4},
        "points": [{"x": 45, "y": 110, "tMs": 0}, {"x": 90, "y": 110, "tMs": 25}],
    }, locked=True)
    third = page("page:blank-letter", 612, 792,
                 {"type": "blank", "color": "#FEFEFC"}, [highlighter])

    pdf_annotation = element("element:pdf-text", "text", {
        "text": "Editable annotation over PDF page 2",
        "frame": {"x": 60, "y": 75, "width": 380, "height": 35},
        "style": {"fontFamily": "serif", "fontSize": 16, "color": "#000000",
                  "bold": False, "italic": True, "underline": False, "alignment": "left"},
    })
    fourth = page("page:pdf-letter", 612, 792,
                  {"type": "pdfPage", "assetId": pdf_asset_id,
                   "pageIndex": 1, "displayBox": "crop"}, [pdf_annotation])

    pages = [first, second, third, fourth]
    manifest = {
        "schemaVersion": 1, "id": identifier("note:mixed-pages"),
        "title": "FMT-03 synthetic mixed-page golden document",
        "createdAt": "2026-10-21T12:00:00Z", "updatedAt": "2026-10-21T12:05:00Z",
        "pages": [{"id": item["id"], "path": f"pages/{item['id']}.json"} for item in pages],
        "assets": [
            {"id": image_asset_id, "path": f"assets/{png_hash}.png",
             "mimeType": "image/png", "sha256": png_hash, "byteLength": len(png)},
            {"id": pdf_asset_id, "path": f"assets/{pdf_hash}.pdf",
             "mimeType": "application/pdf", "sha256": pdf_hash, "byteLength": len(pdf)},
        ],
    }
    documents = {f"pages/{item['id']}.json": item for item in pages}
    assets = {f"assets/{png_hash}.png": png, f"assets/{pdf_hash}.pdf": pdf}
    return manifest, documents, assets


def json_bytes(document: dict) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False,
                       allow_nan=False) + "\n").encode("utf-8")


def build_bytes() -> bytes:
    """Reproducible archive bytes: fixed entry order, timestamps and metadata."""
    from io import BytesIO

    manifest, pages, assets = make_documents()
    members = [("manifest.json", json_bytes(manifest))]
    members += [(item["path"], json_bytes(pages[item["path"]])) for item in manifest["pages"]]
    members += [(item["path"], assets[item["path"]]) for item in manifest["assets"]]
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w") as package:
        for name, content in members:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 21, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            package.writestr(info, content)
    return buffer.getvalue()


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(build_bytes())
    print(f"Wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
