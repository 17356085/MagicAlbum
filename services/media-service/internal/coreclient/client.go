// Package coreclient implements the private Java media callback contract.
// Tokens and response bodies are never included in returned error strings.
package coreclient

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/config"
	"github.com/17356085/MagicAlbum/services/media-service/internal/processor"
	"github.com/17356085/MagicAlbum/services/media-service/internal/wire"
)

const (
	HeaderServiceToken = "X-Internal-Service-Token"
	HeaderLeaseToken   = "X-Media-Lease-Token"
)

type ErrorKind string

const (
	ErrorTransient   ErrorKind = "transient_network"
	ErrorBusy        ErrorKind = "busy"
	ErrorInvalid     ErrorKind = "invalid_message"
	ErrorLease       ErrorKind = "lease_lost"
	ErrorResponse    ErrorKind = "invalid_response"
	ErrorAuth        ErrorKind = "authentication"
	ErrorUnavailable ErrorKind = "core_unavailable"
)

// Error is intentionally status/category only; URL, body and credentials are
// never retained in the message.
type Error struct {
	Operation string
	Status    int
	Kind      ErrorKind
	Cause     error
}

func (e *Error) Error() string {
	if e == nil {
		return ""
	}
	if e.Status > 0 {
		return fmt.Sprintf("core %s failed (%s, status %d)", e.Operation, e.Kind, e.Status)
	}
	return fmt.Sprintf("core %s failed (%s)", e.Operation, e.Kind)
}

func (e *Error) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.Cause
}

func IsKind(err error, kind ErrorKind) bool {
	var target *Error
	return errors.As(err, &target) && target.Kind == kind
}

type Client struct {
	baseURL         *url.URL
	token           string
	httpClient      *http.Client
	maxJSONBytes    int64
	maxInputBytes   int64
	maxOutputBytes  int64
	connectTimeout  time.Duration
	responseTimeout time.Duration
}

func New(cfg config.Config) (*Client, error) {
	if cfg.CoreURL == nil || cfg.CoreURL.Scheme == "" || cfg.CoreURL.Host == "" {
		return nil, errors.New("core client requires a validated core URL")
	}
	if cfg.InternalServiceToken == "" {
		return nil, errors.New("core client requires an internal token")
	}
	connectTimeout := cfg.HTTPConnectTimeout
	if connectTimeout <= 0 {
		connectTimeout = config.DefaultConnectTimeout
	}
	responseTimeout := cfg.HTTPResponseTimeout
	if responseTimeout <= 0 {
		responseTimeout = config.DefaultResponseTimeout
	}
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: connectTimeout, KeepAlive: 30 * time.Second}).DialContext,
		TLSHandshakeTimeout:   connectTimeout,
		ResponseHeaderTimeout: responseTimeout,
		ExpectContinueTimeout: 1 * time.Second,
		MaxIdleConns:          4,
		MaxIdleConnsPerHost:   2,
	}
	return &Client{
		baseURL: cfg.CoreURL,
		token:   cfg.InternalServiceToken,
		httpClient: &http.Client{
			Transport:     transport,
			Timeout:       responseTimeout,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
		},
		maxJSONBytes:    valueOr(cfg.MaxJSONBytes, config.MaxJSONBytes),
		maxInputBytes:   valueOr(cfg.MaxInputBytes, config.MaxInputBytes),
		maxOutputBytes:  valueOr(cfg.MaxOutputBytes, config.MaxOutputBytes),
		connectTimeout:  connectTimeout,
		responseTimeout: responseTimeout,
	}, nil
}

func valueOr(value, fallback int64) int64 {
	if value > 0 {
		return value
	}
	return fallback
}

type ClaimResponse struct {
	Status           string    `json:"status"`
	AssetID          string    `json:"assetId,omitempty"`
	JobVersion       int       `json:"jobVersion,omitempty"`
	Preset           string    `json:"preset,omitempty"`
	ProcessorVersion string    `json:"processorVersion,omitempty"`
	LeaseToken       string    `json:"leaseToken,omitempty"`
	LeaseUntil       time.Time `json:"leaseUntil,omitempty"`
	InputSHA256      string    `json:"inputSha256,omitempty"`
	ContentType      string    `json:"contentType,omitempty"`
	ByteSize         int64     `json:"byteSize,omitempty"`
}

type RenewResponse struct {
	LeaseUntil time.Time `json:"leaseUntil"`
}
type ExecutionResponse struct {
	Status string `json:"status"`
}

func (c *Client) Claim(ctx context.Context, envelope wire.Envelope) (ClaimResponse, error) {
	if err := envelope.Validate(); err != nil {
		return ClaimResponse{}, &Error{Operation: "claim", Kind: ErrorInvalid, Cause: err}
	}
	body, err := json.Marshal(envelope)
	if err != nil {
		return ClaimResponse{}, &Error{Operation: "claim", Kind: ErrorInvalid, Cause: err}
	}
	if int64(len(body)) > c.maxJSONBytes {
		return ClaimResponse{}, &Error{Operation: "claim", Kind: ErrorInvalid}
	}
	var result ClaimResponse
	err = c.doJSON(ctx, http.MethodPost, c.jobPath(envelope.JobID, "claim"), body, "claim", &result)
	if err != nil {
		return ClaimResponse{}, err
	}
	if result.Status == wireStatusClaimed {
		if err := validateClaim(result, envelope, c.maxInputBytes); err != nil {
			return ClaimResponse{}, &Error{Operation: "claim", Kind: ErrorResponse, Cause: err}
		}
		return result, nil
	}
	if result.Status == wireStatusSucceeded || result.Status == wireStatusFailed || result.Status == wireStatusCancelled {
		if hasClaimFields(result) {
			return ClaimResponse{}, &Error{Operation: "claim", Kind: ErrorResponse}
		}
		return result, nil
	}
	return ClaimResponse{}, &Error{Operation: "claim", Kind: ErrorResponse}
}

const (
	wireStatusClaimed   = "CLAIMED"
	wireStatusSucceeded = "SUCCEEDED"
	wireStatusFailed    = "FAILED"
	wireStatusCancelled = "CANCELLED"
)

func validateClaim(response ClaimResponse, envelope wire.Envelope, maxInput int64) error {
	if response.AssetID != envelope.AssetID || response.JobVersion != envelope.JobVersion ||
		response.Preset != envelope.Preset || response.ProcessorVersion != envelope.ProcessorVersion {
		return errors.New("claim binding mismatch")
	}
	if response.LeaseToken == "" || len(response.LeaseToken) > 256 || response.LeaseUntil.IsZero() || !response.LeaseUntil.After(time.Now().UTC()) {
		return errors.New("claim lease is invalid")
	}
	if !isHexDigest(response.InputSHA256) || response.ByteSize <= 0 || response.ByteSize > maxInput {
		return errors.New("claim input binding is invalid")
	}
	if response.ContentType != "image/jpeg" && response.ContentType != "image/png" {
		return errors.New("claim content type is invalid")
	}
	return nil
}

func hasClaimFields(response ClaimResponse) bool {
	return response.AssetID != "" || response.JobVersion != 0 || response.Preset != "" || response.ProcessorVersion != "" || response.LeaseToken != "" || !response.LeaseUntil.IsZero() || response.InputSHA256 != "" || response.ContentType != "" || response.ByteSize != 0
}

func (c *Client) Renew(ctx context.Context, jobID string, jobVersion int, leaseToken string) (RenewResponse, error) {
	if !wireID(jobID) || jobVersion < 1 || leaseToken == "" {
		return RenewResponse{}, &Error{Operation: "renew", Kind: ErrorInvalid}
	}
	body := struct {
		JobVersion int    `json:"jobVersion"`
		LeaseToken string `json:"leaseToken"`
	}{jobVersion, leaseToken}
	data, err := json.Marshal(body)
	if err != nil {
		return RenewResponse{}, &Error{Operation: "renew", Kind: ErrorInvalid, Cause: err}
	}
	var result RenewResponse
	if err := c.doJSON(ctx, http.MethodPost, c.jobPath(jobID, "renew"), data, "renew", &result); err != nil {
		return RenewResponse{}, err
	}
	if result.LeaseUntil.IsZero() || !result.LeaseUntil.After(time.Now().UTC()) {
		return RenewResponse{}, &Error{Operation: "renew", Kind: ErrorResponse}
	}
	return result, nil
}

type Input struct {
	Bytes       []byte
	ContentType string
}

func (c *Client) GetInput(ctx context.Context, jobID string, jobVersion int, leaseToken string) (Input, error) {
	if !wireID(jobID) || jobVersion < 1 || leaseToken == "" {
		return Input{}, &Error{Operation: "input", Kind: ErrorInvalid}
	}
	path := c.jobPath(jobID, "input") + "?jobVersion=" + strconv.Itoa(jobVersion)
	request, err := c.newRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return Input{}, &Error{Operation: "input", Kind: ErrorTransient, Cause: err}
	}
	request.Header.Set(HeaderLeaseToken, leaseToken)
	response, err := c.httpClient.Do(request)
	if err != nil {
		return Input{}, classifyTransport("input", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return Input{}, classifyStatus("input", response.StatusCode)
	}
	if response.ContentLength > c.maxInputBytes {
		return Input{}, &Error{Operation: "input", Kind: ErrorInvalid, Status: response.StatusCode}
	}
	reader := io.LimitReader(response.Body, c.maxInputBytes+1)
	data, err := io.ReadAll(reader)
	if err != nil {
		return Input{}, classifyTransport("input", err)
	}
	if int64(len(data)) == 0 || int64(len(data)) > c.maxInputBytes {
		return Input{}, &Error{Operation: "input", Kind: ErrorInvalid, Status: response.StatusCode}
	}
	return Input{Bytes: data, ContentType: response.Header.Get("Content-Type")}, nil
}

func (c *Client) SubmitResult(ctx context.Context, jobID string, jobVersion int, leaseToken string, result processor.Result, inputSHA string) error {
	if !wireID(jobID) || jobVersion < 1 || leaseToken == "" || !isHexDigest(inputSHA) || len(result.Bytes) == 0 || int64(len(result.Bytes)) > c.maxOutputBytes {
		return &Error{Operation: "result", Kind: ErrorInvalid}
	}
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	filename := "result.jpg"
	if result.ContentType == "image/png" {
		filename = "result.png"
	} else if result.ContentType != "image/jpeg" {
		return &Error{Operation: "result", Kind: ErrorInvalid}
	}
	file, err := writer.CreateFormFile("file", filename)
	if err != nil {
		return &Error{Operation: "result", Kind: ErrorInvalid, Cause: err}
	}
	if _, err := file.Write(result.Bytes); err != nil {
		return &Error{Operation: "result", Kind: ErrorInvalid, Cause: err}
	}
	fields := []struct{ name, value string }{
		{"jobVersion", strconv.Itoa(jobVersion)},
		{"inputSha256", inputSHA},
		{"outputSha256", result.SHA256},
		{"processorVersion", strconv.Itoa(result.ProcessorVersion)},
		{"width", strconv.Itoa(result.Width)},
		{"height", strconv.Itoa(result.Height)},
	}
	for _, field := range fields {
		if err := writer.WriteField(field.name, field.value); err != nil {
			return &Error{Operation: "result", Kind: ErrorInvalid, Cause: err}
		}
	}
	if err := writer.Close(); err != nil {
		return &Error{Operation: "result", Kind: ErrorInvalid, Cause: err}
	}
	request, err := c.newRequest(ctx, http.MethodPost, c.jobPath(jobID, "result"), &body)
	if err != nil {
		return &Error{Operation: "result", Kind: ErrorTransient, Cause: err}
	}
	request.Header.Set("Content-Type", writer.FormDataContentType())
	request.Header.Set(HeaderLeaseToken, leaseToken)
	return c.doJSONRequest(request, "result", func(data []byte) error {
		var response ExecutionResponse
		if err := decodeJSON(data, c.maxJSONBytes, &response); err != nil {
			return err
		}
		if response.Status != wireStatusSucceeded {
			return errors.New("unexpected result status")
		}
		return nil
	})
}

var allowedFailureCodes = map[string]struct{}{
	"UNSUPPORTED_FORMAT": {}, "ANIMATED_IMAGE": {}, "MALFORMED_IMAGE": {}, "INVALID_METADATA": {},
	"IMAGE_LIMITS": {}, "INPUT_TOO_LARGE": {}, "OUTPUT_TOO_LARGE": {}, "ENCODE": {}, "TIMEOUT": {}, "INPUT_MISMATCH": {},
}

func (c *Client) SubmitFailure(ctx context.Context, jobID string, jobVersion int, leaseToken, errorCode string) error {
	if !wireID(jobID) || jobVersion < 1 || leaseToken == "" {
		return &Error{Operation: "failure", Kind: ErrorInvalid}
	}
	if _, ok := allowedFailureCodes[errorCode]; !ok {
		return &Error{Operation: "failure", Kind: ErrorInvalid}
	}
	body := struct {
		JobVersion int    `json:"jobVersion"`
		LeaseToken string `json:"leaseToken"`
		ErrorCode  string `json:"errorCode"`
	}{jobVersion, leaseToken, errorCode}
	data, err := json.Marshal(body)
	if err != nil {
		return &Error{Operation: "failure", Kind: ErrorInvalid, Cause: err}
	}
	var response ExecutionResponse
	if err := c.doJSON(ctx, http.MethodPost, c.jobPath(jobID, "failure"), data, "failure", &response); err != nil {
		return err
	}
	if response.Status != wireStatusFailed {
		return &Error{Operation: "failure", Kind: ErrorResponse}
	}
	return nil
}

func (c *Client) doJSON(ctx context.Context, method, path string, body []byte, operation string, output any) error {
	request, err := c.newRequest(ctx, method, path, bytes.NewReader(body))
	if err != nil {
		return &Error{Operation: operation, Kind: ErrorTransient, Cause: err}
	}
	request.Header.Set("Content-Type", "application/json")
	return c.doJSONRequestDecode(request, operation, output)
}

func (c *Client) doJSONRequestDecode(request *http.Request, operation string, output any) error {
	var data []byte
	response, err := c.httpClient.Do(request)
	if err != nil {
		return classifyTransport(operation, err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return classifyStatus(operation, response.StatusCode)
	}
	data, err = io.ReadAll(io.LimitReader(response.Body, c.maxJSONBytes+1))
	if err != nil {
		return classifyTransport(operation, err)
	}
	if int64(len(data)) > c.maxJSONBytes {
		return &Error{Operation: operation, Kind: ErrorResponse, Status: response.StatusCode}
	}
	if err := decodeJSON(data, c.maxJSONBytes, output); err != nil {
		return &Error{Operation: operation, Kind: ErrorResponse, Cause: err, Status: response.StatusCode}
	}
	return nil
}

func (c *Client) doJSONRequest(request *http.Request, operation string, validate func([]byte) error) error {
	response, err := c.httpClient.Do(request)
	if err != nil {
		return classifyTransport(operation, err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return classifyStatus(operation, response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, c.maxJSONBytes+1))
	if err != nil {
		return classifyTransport(operation, err)
	}
	if int64(len(data)) > c.maxJSONBytes {
		return &Error{Operation: operation, Kind: ErrorResponse, Status: response.StatusCode}
	}
	if err := validate(data); err != nil {
		return &Error{Operation: operation, Kind: ErrorResponse, Status: response.StatusCode, Cause: err}
	}
	return nil
}

func decodeJSON(data []byte, max int64, output any) error {
	if len(data) == 0 || int64(len(data)) > max {
		return errors.New("invalid JSON response")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(output); err != nil {
		return errors.New("invalid JSON response")
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return errors.New("invalid JSON response")
	}
	return nil
}

func (c *Client) newRequest(ctx context.Context, method, path string, body io.Reader) (*http.Request, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if strings.ContainsAny(path, "\r\n") {
		return nil, errors.New("invalid core path")
	}
	u := *c.baseURL
	relative, err := url.Parse(path)
	if err != nil || relative.IsAbs() || relative.Host != "" {
		return nil, errors.New("invalid core path")
	}
	u.Path = strings.TrimRight(c.baseURL.Path, "/") + "/" + strings.TrimLeft(relative.Path, "/")
	u.RawPath = ""
	u.RawQuery = relative.RawQuery
	u.Fragment = ""
	request, err := http.NewRequestWithContext(ctx, method, u.String(), body)
	if err != nil {
		return nil, errors.New("invalid core request")
	}
	request.Header.Set(HeaderServiceToken, c.token)
	request.Header.Set("Cache-Control", "no-store")
	return request, nil
}

func (c *Client) jobPath(jobID, suffix string) string {
	return "internal/media/v1/jobs/" + url.PathEscape(jobID) + "/" + suffix
}

func classifyTransport(operation string, err error) error {
	if err == nil {
		return nil
	}
	return &Error{Operation: operation, Kind: ErrorTransient, Cause: err}
}

func classifyStatus(operation string, status int) error {
	switch status {
	case http.StatusConflict:
		if operation == "claim" {
			return &Error{Operation: operation, Status: status, Kind: ErrorBusy}
		}
		return &Error{Operation: operation, Status: status, Kind: ErrorLease}
	case http.StatusBadRequest, http.StatusNotFound:
		return &Error{Operation: operation, Status: status, Kind: ErrorInvalid}
	case http.StatusUnauthorized, http.StatusForbidden:
		return &Error{Operation: operation, Status: status, Kind: ErrorAuth}
	default:
		return &Error{Operation: operation, Status: status, Kind: ErrorTransient}
	}
}

func isHexDigest(value string) bool {
	if len(value) != sha256.Size*2 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil && strings.ToLower(value) == value
}
func wireID(value string) bool {
	return len(value) == 36 && value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-' && strings.ToLower(value) == value
}
