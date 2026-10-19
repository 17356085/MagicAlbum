package processor

import (
	"bytes"
	"encoding/binary"
)

var (
	pngSignature = []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}
	exifPrefix   = []byte("Exif\x00\x00")
)

type inspectFailureKind uint8

const (
	inspectMalformed inspectFailureKind = iota + 1
	inspectMetadata
	inspectAnimated
)

type inspectFailure struct {
	kind inspectFailureKind
}

func (e *inspectFailure) Error() string { return "input inspection failed" }

func inspectInput(data []byte) (imageFormat, int, error) {
	format, ok := sniffFormat(data)
	if !ok {
		return 0, 0, ErrUnsupportedFormat
	}
	var (
		orientation int
		err         error
	)
	switch format {
	case formatJPEG:
		orientation, err = inspectJPEGMetadata(data)
	case formatPNG:
		orientation, err = inspectPNGMetadata(data)
	}
	if err != nil {
		failure, ok := err.(*inspectFailure)
		if !ok {
			return 0, 0, ErrMalformedImage
		}
		switch failure.kind {
		case inspectMetadata:
			return 0, 0, ErrInvalidMetadata
		case inspectAnimated:
			return 0, 0, ErrAnimatedImage
		default:
			return 0, 0, ErrMalformedImage
		}
	}
	if orientation < 1 || orientation > 8 {
		return 0, 0, ErrInvalidMetadata
	}
	return format, orientation, nil
}

func sniffFormat(data []byte) (imageFormat, bool) {
	if len(data) >= 2 && data[0] == 0xff && data[1] == 0xd8 {
		return formatJPEG, true
	}
	if len(data) >= len(pngSignature) && bytes.Equal(data[:len(pngSignature)], pngSignature) {
		return formatPNG, true
	}
	return 0, false
}

func inspectJPEGMetadata(data []byte) (int, error) {
	if len(data) < 2 || data[0] != 0xff || data[1] != 0xd8 {
		return 0, &inspectFailure{kind: inspectMalformed}
	}
	offset := 2
	orientation := 1
	foundOrientation := false
	foundSOS := false
	for offset < len(data) {
		if data[offset] != 0xff {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		for offset < len(data) && data[offset] == 0xff {
			offset++
		}
		if offset >= len(data) {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		marker := data[offset]
		offset++
		if marker == 0x00 {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		if marker == 0xda { // Start of scan; compressed bytes may contain markers.
			foundSOS = true
			break
		}
		if marker == 0xd9 {
			break
		}
		if marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7) {
			continue
		}
		if offset+2 > len(data) {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		segmentLength := int(binary.BigEndian.Uint16(data[offset : offset+2]))
		if segmentLength < 2 || segmentLength-2 > len(data)-offset-2 {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		segmentStart := offset + 2
		segmentEnd := segmentStart + segmentLength - 2
		segment := data[segmentStart:segmentEnd]
		if marker == 0xe1 && bytes.HasPrefix(segment, []byte("Exif")) {
			if !bytes.HasPrefix(segment, exifPrefix) {
				return 0, &inspectFailure{kind: inspectMetadata}
			}
			parsed, err := parseTIFFOrientation(segment[len(exifPrefix):])
			if err != nil {
				return 0, &inspectFailure{kind: inspectMetadata}
			}
			if foundOrientation {
				return 0, &inspectFailure{kind: inspectMetadata}
			}
			orientation = parsed
			foundOrientation = true
		}
		offset = segmentEnd
	}
	if !foundSOS {
		return 0, &inspectFailure{kind: inspectMalformed}
	}
	return orientation, nil
}

func inspectPNGMetadata(data []byte) (int, error) {
	if len(data) < len(pngSignature) || !bytes.Equal(data[:len(pngSignature)], pngSignature) {
		return 0, &inspectFailure{kind: inspectMalformed}
	}
	offset := len(pngSignature)
	orientation := 1
	foundExif := false
	foundIEND := false
	for offset < len(data) {
		if len(data)-offset < 12 {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		chunkLength := uint64(binary.BigEndian.Uint32(data[offset : offset+4]))
		if chunkLength > uint64(len(data)-offset-12) {
			return 0, &inspectFailure{kind: inspectMalformed}
		}
		chunkType := data[offset+4 : offset+8]
		chunkStart := offset + 8
		chunkEnd := chunkStart + int(chunkLength)
		chunk := data[chunkStart:chunkEnd]
		switch string(chunkType) {
		case "acTL":
			return 0, &inspectFailure{kind: inspectAnimated}
		case "eXIf":
			if foundExif {
				return 0, &inspectFailure{kind: inspectMetadata}
			}
			parsed, err := parseTIFFOrientation(chunk)
			if err != nil {
				return 0, &inspectFailure{kind: inspectMetadata}
			}
			orientation = parsed
			foundExif = true
		case "IEND":
			if chunkLength != 0 {
				return 0, &inspectFailure{kind: inspectMalformed}
			}
			foundIEND = true
		}
		offset = chunkEnd + 4 // Skip CRC.
		if foundIEND {
			break
		}
	}
	if !foundIEND {
		return 0, &inspectFailure{kind: inspectMalformed}
	}
	if offset != len(data) {
		// A valid PNG has no chunks after IEND; trailing data is not image data.
		return 0, &inspectFailure{kind: inspectMalformed}
	}
	return orientation, nil
}

func parseTIFFOrientation(data []byte) (int, error) {
	if bytes.HasPrefix(data, exifPrefix) {
		data = data[len(exifPrefix):]
	}
	if len(data) < 8 {
		return 0, errBadTIFF
	}
	byteOrder := binary.ByteOrder(nil)
	switch {
	case data[0] == 'I' && data[1] == 'I':
		byteOrder = binary.LittleEndian
	case data[0] == 'M' && data[1] == 'M':
		byteOrder = binary.BigEndian
	default:
		return 0, errBadTIFF
	}
	if byteOrder.Uint16(data[2:4]) != 42 {
		return 0, errBadTIFF
	}
	ifdOffset := uint64(byteOrder.Uint32(data[4:8]))
	if ifdOffset < 8 || ifdOffset > uint64(len(data)-2) {
		return 0, errBadTIFF
	}
	ifd := int(ifdOffset)
	count := uint64(byteOrder.Uint16(data[ifd : ifd+2]))
	if count > uint64((len(data)-ifd-2)/12) {
		return 0, errBadTIFF
	}
	entriesEnd := ifd + 2 + int(count)*12
	if entriesEnd > len(data)-4 {
		return 0, errBadTIFF
	}
	nextIFDOffset := uint64(byteOrder.Uint32(data[entriesEnd : entriesEnd+4]))
	if nextIFDOffset != 0 && nextIFDOffset >= uint64(len(data)) {
		return 0, errBadTIFF
	}
	orientation := 1
	found := false
	for index := uint64(0); index < count; index++ {
		entry := ifd + 2 + int(index)*12
		tag := byteOrder.Uint16(data[entry : entry+2])
		if tag != 0x0112 {
			continue
		}
		if found {
			return 0, errBadTIFF
		}
		valueType := byteOrder.Uint16(data[entry+2 : entry+4])
		valueCount := uint64(byteOrder.Uint32(data[entry+4 : entry+8]))
		if valueCount != 1 {
			return 0, errBadTIFF
		}
		switch valueType {
		case 3: // SHORT is the EXIF-defined orientation type.
			orientation = int(byteOrder.Uint16(data[entry+8 : entry+10]))
		case 4: // Accept LONG emitted by a few producers.
			orientation = int(byteOrder.Uint32(data[entry+8 : entry+12]))
		default:
			return 0, errBadTIFF
		}
		if orientation < 1 || orientation > 8 {
			return 0, errBadTIFF
		}
		found = true
	}
	if !found {
		return 1, nil
	}
	return orientation, nil
}

var errBadTIFF = &inspectFailure{kind: inspectMetadata}
