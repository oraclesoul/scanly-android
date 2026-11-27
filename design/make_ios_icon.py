"""Draws the Scanly app icon (same artwork as design/icon.svg) as a 1024x1024 PNG for iOS."""
import sys
from PIL import Image, ImageDraw

S = 4                       # supersampling factor
N = 1024 * S
U = N / 108.0               # icon is designed on a 108 unit grid


def u(v):
    return v * U


def gradient():
    img = Image.new("RGB", (N, N))
    px = img.load()
    c0 = (0x4C, 0x8D, 0xF6)
    c1 = (0x15, 0x58, 0xC8)
    for y in range(N):
        for x in range(N):
            t = (x + y) / (2.0 * (N - 1))
            px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    return img


def arc(d, cx, cy, r, start, end, width, fill):
    box = [u(cx - r), u(cy - r), u(cx + r), u(cy + r)]
    d.arc(box, start, end, fill=fill, width=int(u(width)))


def rounded_line(d, p0, p1, width, fill):
    d.line([(u(p0[0]), u(p0[1])), (u(p1[0]), u(p1[1]))], fill=fill, width=int(u(width)))
    r = u(width) / 2
    for p in (p0, p1):
        d.ellipse([u(p[0]) - r, u(p[1]) - r, u(p[0]) + r, u(p[1]) + r], fill=fill)


def main(out):
    img = gradient()
    d = ImageDraw.Draw(img)
    white = (255, 255, 255)
    w = 4.5

    # viewfinder corners: two straight strokes and a rounded corner each
    rounded_line(d, (29, 41), (29, 33), w, white)
    arc(d, 33, 33, 4, 180, 270, w, white)
    rounded_line(d, (33, 29), (41, 29), w, white)

    rounded_line(d, (67, 29), (75, 29), w, white)
    arc(d, 75, 33, 4, 270, 360, w, white)
    rounded_line(d, (79, 33), (79, 41), w, white)

    rounded_line(d, (79, 67), (79, 75), w, white)
    arc(d, 75, 75, 4, 0, 90, w, white)
    rounded_line(d, (75, 79), (67, 79), w, white)

    rounded_line(d, (41, 79), (33, 79), w, white)
    arc(d, 33, 75, 4, 90, 180, w, white)
    rounded_line(d, (29, 75), (29, 67), w, white)

    # page with folded corner
    d.rounded_rectangle([u(41), u(35), u(67), u(73)], radius=u(4), fill=white)
    d.polygon([(u(59), u(35)), (u(67), u(43)), (u(59), u(43))], fill=(0xBB, 0xD3, 0xF7))

    blue = (0x1A, 0x73, 0xE8)
    for x0, y0, wd in [(46, 39, 10), (46, 44, 16), (46, 57, 16), (46, 62, 16), (46, 67, 10)]:
        d.rectangle([u(x0), u(y0), u(x0 + wd), u(y0 + 2.4)], fill=blue)

    # scan beam
    rounded_line(d, (35, 51), (73, 51), 3.4, (0xFB, 0xBC, 0x04))

    img = img.resize((1024, 1024), Image.LANCZOS)
    img.save(out, "PNG")
    print("saved", out)


if __name__ == "__main__":
    main(sys.argv[1])
