"""构造一个"扫描件"PDF：页面只有图像 XObject，没有任何文本对象。

用途：验证 workspace_read_file 对"抽不出文本"的 PDF 是否给出 notice（而不是裸空串）。
只用标准库（zlib + 手写 PDF 结构），不依赖 MuPDF/PyMuPDF。
"""
import zlib
import struct

W, H = 320, 420


def make_pixels(seed: int) -> bytes:
    """灰阶图像：白底 + 若干条深色横条（像文字行）+ 轻微噪点，让它看起来像扫描件。"""
    rows = []
    for y in range(H):
        row = bytearray()
        for x in range(W):
            v = 245
            # 每 22 像素一行"文字"，行内做不规则断续，模拟扫描的墨迹
            if (y % 22) in (6, 7, 8, 9):
                if ((x * 7919 + seed * 31 + (y // 22) * 131) % 11) > 3:
                    v = 60 + ((x * 13 + y) % 25)
            # 噪点
            v = max(0, min(255, v - ((x * 7 + y * 3 + seed) % 9)))
            row.append(v)
        rows.append(bytes(row))
    return b"".join(rows)


def build_pdf(path: str, pages: int = 2) -> None:
    objects = {}

    page_ids = [4 + i * 2 for i in range(pages)]      # 页对象
    image_ids = [5 + i * 2 for i in range(pages)]     # 图像对象
    content_ids = [6 + i * 2 for i in range(pages)]   # 内容流（如果按 4/5/6 交错需重排）

    # 重排：catalog=1, pages=2, 然后每页一组：page, image, content
    page_ids, image_ids, content_ids = [], [], []
    next_id = 3
    for _ in range(pages):
        page_ids.append(next_id); next_id += 1
        image_ids.append(next_id); next_id += 1
        content_ids.append(next_id); next_id += 1

    objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    objects[2] = f"<< /Type /Pages /Count {pages} /Kids [{kids}] >>".encode()

    for i in range(pages):
        pixels = make_pixels(seed=i)
        compressed = zlib.compress(pixels, 9)
        objects[image_ids[i]] = (
            f"<< /Type /XObject /Subtype /Image /Width {W} /Height {H} "
            f"/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode "
            f"/Length {len(compressed)} >>\nstream\n".encode()
            + compressed
            + b"\nendstream"
        )
        content = f"q 400 0 0 525 100 85 cm /Im0 Do Q".encode()
        objects[content_ids[i]] = (
            f"<< /Length {len(content)} >>\nstream\n".encode() + content + b"\nendstream"
        )
        page_i = page_ids[i]
        objects[page_i] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 700] "
            f"/Resources << /XObject << /Im0 {image_ids[i]} 0 R >> >> "
            f"/Contents {content_ids[i]} 0 R >>"
        ).encode()

    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for oid in sorted(objects):
        offsets[oid] = len(out)
        out += f"{oid} 0 obj\n".encode() + objects[oid] + b"\nendobj\n"

    xref_pos = len(out)
    count = max(objects) + 1
    out += f"xref\n0 {count}\n".encode()
    out += b"0000000000 65535 f \n"
    for oid in range(1, count):
        out += f"{offsets[oid]:010d} 00000 n \n".encode()
    out += (
        f"trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF\n"
    ).encode()

    with open(path, "wb") as f:
        f.write(bytes(out))
    print(f"wrote {path}: {len(out)} bytes, pages={pages}, text objects=0")


if __name__ == "__main__":
    build_pdf("<tmp>/scan-no-text-layer.pdf")
