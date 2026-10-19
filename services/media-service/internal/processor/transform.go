package processor

import (
	"context"
	"image"
	"math"
)

func normalizeNRGBA(ctx context.Context, src image.Image) (*image.NRGBA, error) {
	bounds := src.Bounds()
	dst := image.NewNRGBA(image.Rect(0, 0, bounds.Dx(), bounds.Dy()))
	for y := 0; y < bounds.Dy(); y++ {
		if err := checkContext(ctx); err != nil {
			return nil, err
		}
		for x := 0; x < bounds.Dx(); x++ {
			r, g, b, a := src.At(bounds.Min.X+x, bounds.Min.Y+y).RGBA()
			setNRGBAPixel(dst, x, y, unpremultiply(r, g, b, a))
		}
	}
	return dst, nil
}

func unpremultiply(r, g, b, a uint32) colorNRGBA {
	if a == 0 {
		return colorNRGBA{}
	}
	return colorNRGBA{
		R: uint8((uint64(r)*65535 + uint64(a)/2) / uint64(a) >> 8),
		G: uint8((uint64(g)*65535 + uint64(a)/2) / uint64(a) >> 8),
		B: uint8((uint64(b)*65535 + uint64(a)/2) / uint64(a) >> 8),
		A: uint8((uint64(a) + 128) / 257),
	}
}

// colorNRGBA is kept local to make all pixel conversion explicit and avoid
// repeatedly constructing color interfaces inside the hot loops.
type colorNRGBA struct{ R, G, B, A uint8 }

func applyOrientation(ctx context.Context, src *image.NRGBA, orientation int) (*image.NRGBA, error) {
	if orientation == 1 {
		return src, nil
	}
	srcWidth, srcHeight := src.Bounds().Dx(), src.Bounds().Dy()
	dstWidth, dstHeight := srcWidth, srcHeight
	if orientation >= 5 {
		dstWidth, dstHeight = srcHeight, srcWidth
	}
	dst := image.NewNRGBA(image.Rect(0, 0, dstWidth, dstHeight))
	for y := 0; y < dstHeight; y++ {
		if err := checkContext(ctx); err != nil {
			return nil, err
		}
		for x := 0; x < dstWidth; x++ {
			srcX, srcY := orientationSourcePoint(orientation, x, y, srcWidth, srcHeight)
			setNRGBAPixel(dst, x, y, getNRGBAPixel(src, srcX, srcY))
		}
	}
	return dst, nil
}

func orientationSourcePoint(orientation, x, y, width, height int) (int, int) {
	switch orientation {
	case 2:
		return width - 1 - x, y
	case 3:
		return width - 1 - x, height - 1 - y
	case 4:
		return x, height - 1 - y
	case 5:
		return y, x
	case 6:
		return y, height - 1 - x
	case 7:
		return width - 1 - y, height - 1 - x
	case 8:
		return width - 1 - y, x
	default:
		return x, y
	}
}

func fitDimensions(width, height, box int) (int, int) {
	if width <= box && height <= box {
		return width, height
	}
	if width >= height {
		resultHeight := int((int64(height)*int64(box) + int64(width)/2) / int64(width))
		if resultHeight < 1 {
			resultHeight = 1
		}
		if resultHeight > box {
			resultHeight = box
		}
		return box, resultHeight
	}
	resultWidth := int((int64(width)*int64(box) + int64(height)/2) / int64(height))
	if resultWidth < 1 {
		resultWidth = 1
	}
	if resultWidth > box {
		resultWidth = box
	}
	return resultWidth, box
}

func resizeNRGBA(ctx context.Context, src *image.NRGBA, dstWidth, dstHeight int) (*image.NRGBA, error) {
	if src.Bounds().Dx() == dstWidth && src.Bounds().Dy() == dstHeight {
		return src, nil
	}
	srcWidth, srcHeight := src.Bounds().Dx(), src.Bounds().Dy()
	dst := image.NewNRGBA(image.Rect(0, 0, dstWidth, dstHeight))
	xScale := float64(srcWidth) / float64(dstWidth)
	yScale := float64(srcHeight) / float64(dstHeight)
	for y := 0; y < dstHeight; y++ {
		if err := checkContext(ctx); err != nil {
			return nil, err
		}
		sourceY := (float64(y)+0.5)*yScale - 0.5
		y0 := int(math.Floor(sourceY))
		wy := sourceY - float64(y0)
		if y0 < 0 {
			y0, wy = 0, 0
		}
		y1 := y0 + 1
		if y1 >= srcHeight {
			y1 = srcHeight - 1
		}
		for x := 0; x < dstWidth; x++ {
			sourceX := (float64(x)+0.5)*xScale - 0.5
			x0 := int(math.Floor(sourceX))
			wx := sourceX - float64(x0)
			if x0 < 0 {
				x0, wx = 0, 0
			}
			x1 := x0 + 1
			if x1 >= srcWidth {
				x1 = srcWidth - 1
			}
			p00 := getNRGBAPixel(src, x0, y0)
			p10 := getNRGBAPixel(src, x1, y0)
			p01 := getNRGBAPixel(src, x0, y1)
			p11 := getNRGBAPixel(src, x1, y1)
			setNRGBAPixel(dst, x, y, bilinearPremultiplied(p00, p10, p01, p11, wx, wy))
		}
	}
	return dst, nil
}

func bilinearPremultiplied(p00, p10, p01, p11 colorNRGBA, wx, wy float64) colorNRGBA {
	weights := [4]float64{
		(1 - wx) * (1 - wy),
		wx * (1 - wy),
		(1 - wx) * wy,
		wx * wy,
	}
	alpha := float64(p00.A)*weights[0] + float64(p10.A)*weights[1] + float64(p01.A)*weights[2] + float64(p11.A)*weights[3]
	if alpha <= 0 {
		return colorNRGBA{}
	}
	red := premultipliedChannel(p00.R, p00.A)*weights[0] + premultipliedChannel(p10.R, p10.A)*weights[1] + premultipliedChannel(p01.R, p01.A)*weights[2] + premultipliedChannel(p11.R, p11.A)*weights[3]
	green := premultipliedChannel(p00.G, p00.A)*weights[0] + premultipliedChannel(p10.G, p10.A)*weights[1] + premultipliedChannel(p01.G, p01.A)*weights[2] + premultipliedChannel(p11.G, p11.A)*weights[3]
	blue := premultipliedChannel(p00.B, p00.A)*weights[0] + premultipliedChannel(p10.B, p10.A)*weights[1] + premultipliedChannel(p01.B, p01.A)*weights[2] + premultipliedChannel(p11.B, p11.A)*weights[3]
	return colorNRGBA{
		R: unpremultipliedChannel(red, alpha),
		G: unpremultipliedChannel(green, alpha),
		B: unpremultipliedChannel(blue, alpha),
		A: clampByte(alpha),
	}
}

func premultipliedChannel(channel, alpha uint8) float64 {
	return float64(channel) * float64(alpha) / 255
}

func unpremultipliedChannel(channel, alpha float64) uint8 {
	return clampByte(channel * 255 / alpha)
}

func clampByte(value float64) uint8 {
	if value <= 0 {
		return 0
	}
	if value >= 255 {
		return 255
	}
	return uint8(math.Round(value))
}

func getNRGBAPixel(src *image.NRGBA, x, y int) colorNRGBA {
	i := y*src.Stride + x*4
	return colorNRGBA{R: src.Pix[i], G: src.Pix[i+1], B: src.Pix[i+2], A: src.Pix[i+3]}
}

func setNRGBAPixel(dst *image.NRGBA, x, y int, pixel colorNRGBA) {
	i := y*dst.Stride + x*4
	dst.Pix[i] = pixel.R
	dst.Pix[i+1] = pixel.G
	dst.Pix[i+2] = pixel.B
	dst.Pix[i+3] = pixel.A
}
