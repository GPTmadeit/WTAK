#!/usr/bin/env python3
"""
Build a small, valid .mbtiles archive for testing the offline map path.

Generates solid-colour tiles over a bounding box for a range of zoom levels, so
you can confirm the app renders entirely from local storage with the network
off. Real archives come from tools like MOBAC, QGIS or `mbutil`; this exists so
the offline code path can be exercised without downloading a basemap.

    python tools/make_test_mbtiles.py out.mbtiles --lat 40.758 --lon -73.9855

Then push it to the watch:

    adb push out.mbtiles /sdcard/Android/data/com.atakwatch.minimap/files/maps/
"""
import argparse
import math
import sqlite3
import struct
import zlib


def png_solid(size: int, rgb: tuple[int, int, int]) -> bytes:
    """Minimal solid-colour RGB PNG, no external imaging library needed."""
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def deg2tile(lat: float, lon: float, z: int) -> tuple[int, int]:
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    rad = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(rad)) / math.pi) / 2.0 * n)
    return x, y


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("output")
    ap.add_argument("--lat", type=float, default=40.7580)
    ap.add_argument("--lon", type=float, default=-73.9855)
    ap.add_argument("--min-zoom", type=int, default=12)
    ap.add_argument("--max-zoom", type=int, default=17)
    ap.add_argument("--radius", type=int, default=3,
                    help="tiles to include either side of centre, per zoom")
    args = ap.parse_args()

    db = sqlite3.connect(args.output)
    db.executescript("""
        DROP TABLE IF EXISTS tiles;
        DROP TABLE IF EXISTS metadata;
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER,
                            tile_row INTEGER, tile_data BLOB);
        CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
        CREATE TABLE metadata (name TEXT, value TEXT);
    """)

    for key, value in [
        ("name", "offline-test"), ("type", "baselayer"), ("version", "1.0"),
        ("description", "Solid-colour test tiles for ATAK Watch"),
        ("format", "png"),
        ("minzoom", str(args.min_zoom)), ("maxzoom", str(args.max_zoom)),
    ]:
        db.execute("INSERT INTO metadata VALUES (?, ?)", (key, value))

    # Alternate two muted tones per zoom so the tile grid is visibly local.
    palette = [(38, 54, 44), (46, 64, 52)]
    count = 0
    for z in range(args.min_zoom, args.max_zoom + 1):
        cx, cy = deg2tile(args.lat, args.lon, z)
        n = 2 ** z
        for x in range(cx - args.radius, cx + args.radius + 1):
            for y in range(cy - args.radius, cy + args.radius + 1):
                if not (0 <= x < n and 0 <= y < n):
                    continue
                # MBTiles rows are TMS: flipped relative to XYZ.
                tms_y = n - 1 - y
                blob = png_solid(256, palette[(x + y) % 2])
                db.execute(
                    "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)",
                    (z, x, tms_y, sqlite3.Binary(blob)),
                )
                count += 1

    db.commit()
    db.close()
    print(f"wrote {args.output}: {count} tiles, zoom {args.min_zoom}-{args.max_zoom}")


if __name__ == "__main__":
    main()
