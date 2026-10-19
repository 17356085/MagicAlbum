package processor

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"sync"
	"testing"
)

func TestTransformJPEGThumbnailAndResultMetadata(t *testing.T) {
	input := encodeJPEGFixture(t, 1200, 600)
	result, err := Transform(context.Background(), bytes.NewReader(input), PresetThumbnail)
	if err != nil {
		t.Fatalf("Transform: %v", err)
	}
	if result.ContentType != "image/jpeg" || result.Width != 480 || result.Height != 240 {
		t.Fatalf("unexpected result: content=%q size=%dx%d", result.ContentType, result.Width, result.Height)
	}
	if result.Preset != PresetThumbnail || result.ProcessorVersion != CurrentProcessorVersion {
		t.Fatalf("unexpected contract fields: %+v", result)
	}
	if len(result.Bytes) == 0 || result.SHA256 == "" || !bytes.Equal(result.SHA256Bytes[:], mustSHA256(result.Bytes)) {
		t.Fatal("result digest is missing or inconsistent")
	}
	decoded, err := jpeg.Decode(bytes.NewReader(result.Bytes))
	if err != nil {
		t.Fatalf("decode output: %v", err)
	}
	if got := decoded.Bounds().Size(); got.X != 480 || got.Y != 240 {
		t.Fatalf("encoded dimensions = %v", got)
	}
}

func TestTransformPNGPreservesTransparencyAndStripsText(t *testing.T) {
	input := encodePNGFixture(t, 1000, 500)
	input = insertPNGChunkAfterIHDR(t, input, "tEXt", []byte("Comment\x00secret"))
	result, err := Transform(context.Background(), bytes.NewReader(input), PresetThumbnail)
	if err != nil {
		t.Fatalf("Transform: %v", err)
	}
	if result.ContentType != "image/png" || result.Width != 480 || result.Height != 240 {
		t.Fatalf("unexpected result: content=%q size=%dx%d", result.ContentType, result.Width, result.Height)
	}
	if bytes.Contains(result.Bytes, []byte("secret")) || bytes.Contains(result.Bytes, []byte("tEXt")) {
		t.Fatal("source PNG text metadata was copied")
	}
	decoded, err := png.Decode(bytes.NewReader(result.Bytes))
	if err != nil {
		t.Fatalf("decode output: %v", err)
	}
	_, _, _, alpha := decoded.At(0, 0).RGBA()
	if alpha == 0xffff {
		t.Fatal("transparent source corner became opaque")
	}
}

func TestTransformDoesNotUpscale(t *testing.T) {
	input := encodePNGFixture(t, 80, 40)
	result, err := Transform(context.Background(), bytes.NewReader(input), PresetPreview)
	if err != nil {
		t.Fatalf("Transform: %v", err)
	}
	if result.Width != 80 || result.Height != 40 {
		t.Fatalf("small input was upscaled to %dx%d", result.Width, result.Height)
	}
}

func TestJPEGOrientationAllValuesAndOrientation8Pixels(t *testing.T) {
	base := encodeJPEGOrientationFixture(t)
	for orientation := 1; orientation <= 8; orientation++ {
		input := addJPEGEXIFOrientation(base, orientation)
		result, err := Transform(context.Background(), bytes.NewReader(input), PresetPreview)
		if err != nil {
			t.Fatalf("orientation %d: Transform: %v", orientation, err)
		}
		wantWidth, wantHeight := 4, 2
		if orientation >= 5 {
			wantWidth, wantHeight = 2, 4
		}
		if result.Width != wantWidth || result.Height != wantHeight {
			t.Fatalf("orientation %d: size=%dx%d, want %dx%d", orientation, result.Width, result.Height, wantWidth, wantHeight)
		}
	}

	result, err := Transform(context.Background(), bytes.NewReader(addJPEGEXIFOrientation(base, 8)), PresetPreview)
	if err != nil {
		t.Fatalf("orientation 8: Transform: %v", err)
	}
	decoded, err := jpeg.Decode(bytes.NewReader(result.Bytes))
	if err != nil {
		t.Fatalf("decode orientation 8 output: %v", err)
	}
	// Source top-right is blue; EXIF orientation 8 moves it to the output top-left.
	r, g, b, _ := decoded.At(0, 0).RGBA()
	if b <= r || b <= g {
		t.Fatalf("orientation 8 top-left pixel is not source top-right blue: %d,%d,%d", r, g, b)
	}
	if bytes.Contains(result.Bytes, []byte("Exif")) {
		t.Fatal("EXIF metadata was copied to JPEG output")
	}
}

func TestPNGEXIFOrientation(t *testing.T) {
	base := encodePNGOrientationFixture(t)
	input := addPNGEXIFOrientation(t, base, 8)
	result, err := Transform(context.Background(), bytes.NewReader(input), PresetPreview)
	if err != nil {
		t.Fatalf("Transform: %v", err)
	}
	if result.Width != 2 || result.Height != 4 {
		t.Fatalf("size=%dx%d, want 2x4", result.Width, result.Height)
	}
	if bytes.Contains(result.Bytes, []byte("eXIf")) {
		t.Fatal("eXIf metadata was copied to PNG output")
	}
}

func TestPNGEXIFOrientationAllValuesMoveCorners(t *testing.T) {
	base := encodePNGOrientationFixture(t)
	// Expected source coordinates for the output's top-left corner are written
	// independently of orientationSourcePoint so this catches mapping errors.
	wantSource := map[int][2]int{
		1: {0, 0},
		2: {3, 0},
		3: {3, 1},
		4: {0, 1},
		5: {0, 0},
		6: {0, 1},
		7: {3, 1},
		8: {3, 0},
	}
	for orientation := 1; orientation <= 8; orientation++ {
		result, err := Transform(context.Background(), bytes.NewReader(addPNGEXIFOrientation(t, base, orientation)), PresetPreview)
		if err != nil {
			t.Fatalf("orientation %d: Transform: %v", orientation, err)
		}
		decoded, err := png.Decode(bytes.NewReader(result.Bytes))
		if err != nil {
			t.Fatalf("orientation %d: decode: %v", orientation, err)
		}
		xy := wantSource[orientation]
		want := orientationFixtureColor(xy[0], xy[1])
		got := color.NRGBAModel.Convert(decoded.At(0, 0)).(color.NRGBA)
		if got != want {
			t.Fatalf("orientation %d top-left=%+v, want source(%d,%d)=%+v", orientation, got, xy[0], xy[1], want)
		}
	}
}

func TestRejectsMalformedAndUnsupportedInputs(t *testing.T) {
	validPNG := encodePNGFixture(t, 16, 8)
	validJPEG := encodeJPEGFixture(t, 16, 8)
	cases := []struct {
		name  string
		input []byte
		want  *Error
	}{
		{name: "gif", input: []byte("GIF89a"), want: ErrUnsupportedFormat},
		{name: "webp", input: []byte("RIFFxxxxWEBP"), want: ErrUnsupportedFormat},
		{name: "svg", input: []byte("<svg></svg>"), want: ErrUnsupportedFormat},
		{name: "truncated png", input: validPNG[:len(validPNG)-4], want: ErrMalformedImage},
		{name: "truncated jpeg", input: validJPEG[:len(validJPEG)/2], want: ErrMalformedImage},
		{name: "bad reader", input: nil, want: ErrInputRead},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var reader io.Reader = bytes.NewReader(tc.input)
			if tc.name == "bad reader" {
				reader = errorReader{}
			}
			_, err := Transform(context.Background(), reader, PresetThumbnail)
			if !errors.Is(err, tc.want) {
				t.Fatalf("error=%v, want category %s", err, tc.want)
			}
			if err != nil && err.Error() != tc.want.Error() {
				t.Fatalf("error text=%q exposes unexpected detail", err.Error())
			}
		})
	}
}

func TestRejectsLimitsAPNGBadEXIFAndUnknownPreset(t *testing.T) {
	cases := []struct {
		name   string
		input  []byte
		preset Preset
		want   *Error
	}{
		{name: "edge limit", input: pngHeaderOnly(20_001, 1), preset: PresetThumbnail, want: ErrImageLimits},
		{name: "pixel limit", input: pngHeaderOnly(6_000, 4_001), preset: PresetThumbnail, want: ErrImageLimits},
		{name: "apng", input: addPNGChunk(t, encodePNGFixture(t, 8, 8), "acTL", make([]byte, 8)), preset: PresetThumbnail, want: ErrAnimatedImage},
		{name: "bad png exif", input: addPNGChunk(t, encodePNGFixture(t, 8, 8), "eXIf", []byte("bad")), preset: PresetThumbnail, want: ErrInvalidMetadata},
		{name: "bad png exif offset", input: addPNGChunk(t, encodePNGFixture(t, 8, 8), "eXIf", badTIFFOffset()), preset: PresetThumbnail, want: ErrInvalidMetadata},
		{name: "truncated png exif", input: addPNGChunk(t, encodePNGFixture(t, 8, 8), "eXIf", makeTIFFOrientation(8)[:len(makeTIFFOrientation(8))-4]), preset: PresetThumbnail, want: ErrInvalidMetadata},
		{name: "bad png exif next offset", input: addPNGChunk(t, encodePNGFixture(t, 8, 8), "eXIf", badTIFFNextOffset()), preset: PresetThumbnail, want: ErrInvalidMetadata},
		{name: "unknown preset", input: encodePNGFixture(t, 8, 8), preset: Preset("large"), want: ErrInvalidPreset},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Transform(context.Background(), bytes.NewReader(tc.input), tc.preset)
			if !errors.Is(err, tc.want) {
				t.Fatalf("error=%v, want category %s", err, tc.want)
			}
		})
	}
	if _, err := Transform(context.Background(), bytes.NewReader(make([]byte, MaxInputBytes+1)), PresetThumbnail); !errors.Is(err, ErrInputTooLarge) {
		t.Fatalf("oversized input error=%v, want %s", err, ErrInputTooLarge)
	}
	if _, err := Transform(context.Background(), bytes.NewReader(addJPEGEXIFOrientation(encodeJPEGFixture(t, 8, 8), 9)), PresetThumbnail); !errors.Is(err, ErrInvalidMetadata) {
		t.Fatalf("bad JPEG orientation error=%v, want %s", err, ErrInvalidMetadata)
	}
	if err := validateDimensions(6_000, 4_000); err != nil {
		t.Fatalf("exact 24,000,000-pixel boundary rejected: %v", err)
	}
}

func TestCancellationAndReaderContextCooperation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := Transform(ctx, bytes.NewReader(encodePNGFixture(t, 32, 32)), PresetThumbnail)
	if !errors.Is(err, ErrCanceled) || !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled transform error=%v", err)
	}

	ctx, cancel = context.WithCancel(context.Background())
	reader := cancelingReader{cancel: cancel, data: encodePNGFixture(t, 32, 32)}
	_, err = Transform(ctx, &reader, PresetThumbnail)
	if !errors.Is(err, ErrCanceled) {
		t.Fatalf("reader cancellation error=%v", err)
	}
}

func TestDeterministicHashAndConcurrentTransforms(t *testing.T) {
	input := encodePNGFixture(t, 320, 160)
	first, err := Transform(context.Background(), bytes.NewReader(input), PresetThumbnail)
	if err != nil {
		t.Fatal(err)
	}
	second, err := Transform(context.Background(), bytes.NewReader(input), PresetThumbnail)
	if err != nil {
		t.Fatal(err)
	}
	if first.SHA256 != second.SHA256 || !bytes.Equal(first.Bytes, second.Bytes) {
		t.Fatal("repeated transforms are not deterministic")
	}

	const workers = 8
	results := make(chan Result, workers)
	errs := make(chan error, workers)
	var wait sync.WaitGroup
	for i := 0; i < workers; i++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			result, err := Transform(context.Background(), bytes.NewReader(input), PresetThumbnail)
			if err != nil {
				errs <- err
				return
			}
			results <- result
		}()
	}
	wait.Wait()
	close(results)
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	for result := range results {
		if result.SHA256 != first.SHA256 || !bytes.Equal(result.Bytes, first.Bytes) {
			t.Fatal("concurrent transform differs from baseline")
		}
	}
}

func TestPremultipliedInterpolationAvoidsTransparentDarkHalo(t *testing.T) {
	result := bilinearPremultiplied(
		colorNRGBA{A: 0}, colorNRGBA{R: 255, A: 255},
		colorNRGBA{A: 0}, colorNRGBA{R: 255, A: 255},
		0.5, 0.5,
	)
	if result.A < 120 || result.A > 135 || result.R < 245 {
		t.Fatalf("premultiplied result=%+v, want half-alpha white", result)
	}
}

func TestBoundedWriterRejectsBudgetAndCancellation(t *testing.T) {
	var output bytes.Buffer
	writer := &boundedWriter{ctx: context.Background(), dst: &output, remaining: 3}
	if n, err := writer.Write([]byte("abcd")); n != 0 || !errors.Is(err, errOutputTooLarge) || output.Len() != 0 {
		t.Fatalf("oversized write n=%d err=%v output=%d", n, err, output.Len())
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	writer = &boundedWriter{ctx: ctx, dst: &output, remaining: 3}
	if n, err := writer.Write([]byte("x")); n != 0 || !errors.Is(err, errContextCanceled) || output.Len() != 0 {
		t.Fatalf("cancelled write n=%d err=%v output=%d", n, err, output.Len())
	}
}

func encodePNGFixture(t *testing.T, width, height int) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			alpha := uint8(255)
			if x < width/3 {
				alpha = uint8((x + y) % 160)
			}
			img.SetNRGBA(x, y, color.NRGBA{R: uint8(x % 256), G: uint8(y % 256), B: 200, A: alpha})
		}
	}
	var output bytes.Buffer
	if err := png.Encode(&output, img); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}

func encodePNGOrientationFixture(t *testing.T) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, 4, 2))
	for y := 0; y < 2; y++ {
		for x := 0; x < 4; x++ {
			img.SetNRGBA(x, y, orientationFixtureColor(x, y))
		}
	}
	var output bytes.Buffer
	if err := png.Encode(&output, img); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}

func orientationFixtureColor(x, y int) color.NRGBA {
	pixel := color.NRGBA{R: 220, A: 255}
	if x >= 2 {
		pixel = color.NRGBA{B: 220, A: 255}
	}
	if y == 1 {
		pixel.G = 100
	}
	return pixel
}

func encodeJPEGFixture(t *testing.T, width, height int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			img.SetRGBA(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 180, A: 255})
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}

func encodeJPEGOrientationFixture(t *testing.T) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 4, 2))
	for y := 0; y < 2; y++ {
		for x := 0; x < 4; x++ {
			pixel := color.RGBA{R: 220, A: 255}
			if x >= 2 {
				pixel = color.RGBA{B: 220, A: 255}
			}
			if y == 1 {
				pixel.G = 100
			}
			img.SetRGBA(x, y, pixel)
		}
	}
	var output bytes.Buffer
	if err := jpeg.Encode(&output, img, &jpeg.Options{Quality: 100}); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}

func makeTIFFOrientation(orientation int) []byte {
	data := make([]byte, 8+2+12+4)
	data[0], data[1] = 'I', 'I'
	binary.LittleEndian.PutUint16(data[2:4], 42)
	binary.LittleEndian.PutUint32(data[4:8], 8)
	binary.LittleEndian.PutUint16(data[8:10], 1)
	binary.LittleEndian.PutUint16(data[10:12], 0x0112)
	binary.LittleEndian.PutUint16(data[12:14], 3)
	binary.LittleEndian.PutUint32(data[14:18], 1)
	binary.LittleEndian.PutUint16(data[18:20], uint16(orientation))
	return data
}

func badTIFFOffset() []byte {
	data := makeTIFFOrientation(8)
	binary.LittleEndian.PutUint32(data[4:8], 4)
	return data
}

func badTIFFNextOffset() []byte {
	data := makeTIFFOrientation(8)
	binary.LittleEndian.PutUint32(data[len(data)-4:], uint32(len(data)+1))
	return data
}

func addJPEGEXIFOrientation(data []byte, orientation int) []byte {
	payload := append([]byte("Exif\x00\x00"), makeTIFFOrientation(orientation)...)
	segment := make([]byte, 4+len(payload))
	segment[0], segment[1] = 0xff, 0xe1
	binary.BigEndian.PutUint16(segment[2:4], uint16(len(payload)+2))
	copy(segment[4:], payload)
	return append(append(append([]byte(nil), data[:2]...), segment...), data[2:]...)
}

func addPNGEXIFOrientation(t *testing.T, data []byte, orientation int) []byte {
	t.Helper()
	return insertPNGChunkAfterIHDR(t, data, "eXIf", makeTIFFOrientation(orientation))
}

func insertPNGChunkAfterIHDR(t *testing.T, data []byte, chunkType string, payload []byte) []byte {
	t.Helper()
	if len(data) < 8+12 {
		t.Fatal("fixture PNG is too short")
	}
	firstLength := int(binary.BigEndian.Uint32(data[8:12]))
	insertAt := 8 + 12 + firstLength
	chunk := makePNGChunk(chunkType, payload)
	return append(append(append([]byte(nil), data[:insertAt]...), chunk...), data[insertAt:]...)
}

func addPNGChunk(t *testing.T, data []byte, chunkType string, payload []byte) []byte {
	t.Helper()
	return insertPNGChunkAfterIHDR(t, data, chunkType, payload)
}

func makePNGChunk(chunkType string, payload []byte) []byte {
	chunk := make([]byte, 12+len(payload))
	binary.BigEndian.PutUint32(chunk[:4], uint32(len(payload)))
	copy(chunk[4:8], chunkType)
	copy(chunk[8:8+len(payload)], payload)
	crc := crc32.NewIEEE()
	_, _ = crc.Write(chunk[4 : 8+len(payload)])
	binary.BigEndian.PutUint32(chunk[8+len(payload):], crc.Sum32())
	return chunk
}

func pngHeaderOnly(width, height int) []byte {
	data := append([]byte(nil), pngSignature...)
	ihdr := make([]byte, 13)
	binary.BigEndian.PutUint32(ihdr[0:4], uint32(width))
	binary.BigEndian.PutUint32(ihdr[4:8], uint32(height))
	ihdr[8] = 8 // bit depth
	ihdr[9] = 6 // RGBA
	data = append(data, makePNGChunk("IHDR", ihdr)...)
	data = append(data, makePNGChunk("IEND", nil)...)
	return data
}

func mustSHA256(data []byte) []byte {
	digest := sha256.Sum256(data)
	result := digest
	return result[:]
}

type errorReader struct{}

func (errorReader) Read([]byte) (int, error) { return 0, errors.New("private reader detail") }

type cancelingReader struct {
	cancel context.CancelFunc
	data   []byte
	done   bool
}

func (r *cancelingReader) Read(p []byte) (int, error) {
	if !r.done {
		r.done = true
		r.cancel()
	}
	return copy(p, r.data), io.EOF
}
