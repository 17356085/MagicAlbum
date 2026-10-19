// Package processor contains the bounded, deterministic image transformation
// kernel used by the media-service module.
//
// It deliberately has no transport, queue, storage, or application-service
// dependencies. Callers provide an input stream and receive encoded bytes.
package processor

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"image"
	"image/jpeg"
	"image/png"
	"io"
)

const (
	// MaxInputBytes bounds the complete encoded input held by Transform.
	MaxInputBytes int64 = 10 * 1024 * 1024
	// MaxOutputBytes bounds the complete encoded output produced by Transform.
	MaxOutputBytes int64 = 10 * 1024 * 1024
	// MaxPixels bounds decoded image area before pixel memory is allocated.
	MaxPixels uint64 = 24_000_000
	// MaxDimension bounds either decoded image edge.
	MaxDimension = 20_000
	// JPEGQuality is fixed so the same input and preset have stable output.
	JPEGQuality = 82
	// CurrentProcessorVersion identifies this implementation's output contract.
	CurrentProcessorVersion = 1
)

// Preset identifies a supported output size.
type Preset string

const (
	PresetThumbnail Preset = "thumbnail"
	PresetPreview   Preset = "preview"

	// Short aliases keep call sites readable while retaining the explicit names.
	Thumbnail = PresetThumbnail
	Preview   = PresetPreview
)

// Result is the encoded image and the metadata needed by a caller to persist
// or describe it. Bytes never contain source metadata such as EXIF, GPS, or
// textual PNG chunks.
type Result struct {
	Bytes            []byte
	ContentType      string
	Width            int
	Height           int
	SHA256           string
	SHA256Bytes      [sha256.Size]byte
	Preset           Preset
	ProcessorVersion int
}

// ErrorKind is a stable, non-sensitive failure category. Error messages are
// intentionally category-only and never include source bytes, paths, or
// underlying decoder text.
type ErrorKind string

const (
	ErrorKindInvalidPreset     ErrorKind = "invalid_preset"
	ErrorKindInputRead         ErrorKind = "input_read"
	ErrorKindInputTooLarge     ErrorKind = "input_too_large"
	ErrorKindUnsupportedFormat ErrorKind = "unsupported_format"
	ErrorKindAnimatedImage     ErrorKind = "animated_image"
	ErrorKindMalformedImage    ErrorKind = "malformed_image"
	ErrorKindInvalidMetadata   ErrorKind = "invalid_metadata"
	ErrorKindImageLimits       ErrorKind = "image_limits"
	ErrorKindOutputTooLarge    ErrorKind = "output_too_large"
	ErrorKindEncode            ErrorKind = "encode"
	ErrorKindCanceled          ErrorKind = "canceled"
)

// Error is returned for all expected processing failures. The unexported
// cause supports errors.Is for context cancellation without exposing its text.
type Error struct {
	Kind  ErrorKind
	cause error
}

func (e *Error) Error() string {
	if e == nil {
		return ""
	}
	return string(e.Kind)
}

func (e *Error) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.cause
}

func (e *Error) Is(target error) bool {
	other, ok := target.(*Error)
	return ok && e != nil && other != nil && e.Kind == other.Kind
}

var (
	ErrInvalidPreset     = &Error{Kind: ErrorKindInvalidPreset}
	ErrInputRead         = &Error{Kind: ErrorKindInputRead}
	ErrInputTooLarge     = &Error{Kind: ErrorKindInputTooLarge}
	ErrUnsupportedFormat = &Error{Kind: ErrorKindUnsupportedFormat}
	ErrAnimatedImage     = &Error{Kind: ErrorKindAnimatedImage}
	ErrMalformedImage    = &Error{Kind: ErrorKindMalformedImage}
	ErrInvalidMetadata   = &Error{Kind: ErrorKindInvalidMetadata}
	ErrImageLimits       = &Error{Kind: ErrorKindImageLimits}
	ErrOutputTooLarge    = &Error{Kind: ErrorKindOutputTooLarge}
	ErrEncode            = &Error{Kind: ErrorKindEncode}
	ErrCanceled          = &Error{Kind: ErrorKindCanceled}
	errContextCanceled   = errors.New("context canceled")
	errOutputTooLarge    = errors.New("output too large")
)

type imageFormat uint8

const (
	formatJPEG imageFormat = iota + 1
	formatPNG
)

type presetSpec struct {
	name Preset
	box  int
}

func specForPreset(preset Preset) (presetSpec, bool) {
	switch preset {
	case PresetThumbnail:
		return presetSpec{name: preset, box: 480}, true
	case PresetPreview:
		return presetSpec{name: preset, box: 1600}, true
	default:
		return presetSpec{}, false
	}
}

// Transform decodes one static JPEG or PNG, applies orientation, fits it
// inside the requested preset without upscaling, and re-encodes it without
// source metadata.
func Transform(ctx context.Context, input io.Reader, preset Preset) (Result, error) {
	var zero Result
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return zero, canceledError(err)
	}
	spec, ok := specForPreset(preset)
	if !ok {
		return zero, ErrInvalidPreset
	}

	data, err := readInput(ctx, input)
	if err != nil {
		return zero, err
	}

	format, orientation, err := inspectInput(data)
	if err != nil {
		return zero, err
	}
	if err := checkContext(ctx); err != nil {
		return zero, err
	}

	config, err := decodeConfig(format, data)
	if err != nil {
		return zero, ErrMalformedImage
	}
	if err := validateDimensions(config.Width, config.Height); err != nil {
		return zero, err
	}

	decoded, err := decodeImage(format, data)
	if err != nil {
		return zero, ErrMalformedImage
	}
	if decoded.Bounds().Dx() != config.Width || decoded.Bounds().Dy() != config.Height {
		return zero, ErrMalformedImage
	}
	if err := checkContext(ctx); err != nil {
		return zero, err
	}

	normalized, err := normalizeNRGBA(ctx, decoded)
	if err != nil {
		return zero, err
	}
	if orientation != 1 {
		normalized, err = applyOrientation(ctx, normalized, orientation)
		if err != nil {
			return zero, err
		}
	}

	dstWidth, dstHeight := fitDimensions(normalized.Bounds().Dx(), normalized.Bounds().Dy(), spec.box)
	resized, err := resizeNRGBA(ctx, normalized, dstWidth, dstHeight)
	if err != nil {
		return zero, err
	}

	var encoded bytes.Buffer
	writer := &boundedWriter{ctx: ctx, dst: &encoded, remaining: MaxOutputBytes}
	switch format {
	case formatJPEG:
		err = jpeg.Encode(writer, resized, &jpeg.Options{Quality: JPEGQuality})
	case formatPNG:
		err = png.Encode(writer, resized)
	default:
		return zero, ErrUnsupportedFormat
	}
	if err != nil {
		switch {
		case errors.Is(err, errContextCanceled), ctx.Err() != nil:
			return zero, canceledError(ctx.Err())
		case errors.Is(err, errOutputTooLarge):
			return zero, ErrOutputTooLarge
		default:
			return zero, ErrEncode
		}
	}
	if err := checkContext(ctx); err != nil {
		return zero, err
	}
	if int64(encoded.Len()) > MaxOutputBytes {
		return zero, ErrOutputTooLarge
	}

	digest := sha256.Sum256(encoded.Bytes())
	resultBytes := append([]byte(nil), encoded.Bytes()...)
	return Result{
		Bytes:            resultBytes,
		ContentType:      contentType(format),
		Width:            resized.Bounds().Dx(),
		Height:           resized.Bounds().Dy(),
		SHA256:           hex.EncodeToString(digest[:]),
		SHA256Bytes:      digest,
		Preset:           spec.name,
		ProcessorVersion: CurrentProcessorVersion,
	}, nil
}

func readInput(ctx context.Context, input io.Reader) ([]byte, error) {
	if input == nil {
		return nil, ErrInputRead
	}
	reader := io.LimitReader(&contextReader{ctx: ctx, reader: input}, MaxInputBytes+1)
	data, err := io.ReadAll(reader)
	if ctx.Err() != nil {
		return nil, canceledError(ctx.Err())
	}
	if err != nil {
		return nil, ErrInputRead
	}
	if int64(len(data)) > MaxInputBytes {
		return nil, ErrInputTooLarge
	}
	if len(data) == 0 {
		return nil, ErrMalformedImage
	}
	return data, nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r *contextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, errContextCanceled
	}
	n, err := r.reader.Read(p)
	if r.ctx.Err() != nil {
		return n, errContextCanceled
	}
	return n, err
}

type boundedWriter struct {
	ctx       context.Context
	dst       *bytes.Buffer
	remaining int64
}

func (w *boundedWriter) Write(p []byte) (int, error) {
	if err := w.ctx.Err(); err != nil {
		return 0, errContextCanceled
	}
	if int64(len(p)) > w.remaining {
		return 0, errOutputTooLarge
	}
	n, err := w.dst.Write(p)
	w.remaining -= int64(n)
	return n, err
}

func checkContext(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return canceledError(err)
	}
	return nil
}

func canceledError(cause error) error {
	if cause == nil {
		cause = context.Canceled
	}
	return &Error{Kind: ErrorKindCanceled, cause: cause}
}

func validateDimensions(width, height int) error {
	if width <= 0 || height <= 0 {
		return ErrMalformedImage
	}
	if width > MaxDimension || height > MaxDimension {
		return ErrImageLimits
	}
	if uint64(width) > MaxPixels/uint64(height) {
		return ErrImageLimits
	}
	return nil
}

func contentType(format imageFormat) string {
	if format == formatPNG {
		return "image/png"
	}
	return "image/jpeg"
}

func decodeConfig(format imageFormat, data []byte) (image.Config, error) {
	switch format {
	case formatJPEG:
		return jpeg.DecodeConfig(bytes.NewReader(data))
	case formatPNG:
		return png.DecodeConfig(bytes.NewReader(data))
	default:
		return image.Config{}, errors.New("unknown format")
	}
}

func decodeImage(format imageFormat, data []byte) (image.Image, error) {
	switch format {
	case formatJPEG:
		return jpeg.Decode(bytes.NewReader(data))
	case formatPNG:
		return png.Decode(bytes.NewReader(data))
	default:
		return nil, errors.New("unknown format")
	}
}
