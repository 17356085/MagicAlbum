package consumer

import (
	"net/http"
	"sync/atomic"
)

type Health struct{ consuming atomic.Bool }

func NewHealth() *Health { return &Health{} }
func (h *Health) SetConsuming(value bool) {
	if h != nil {
		h.consuming.Store(value)
	}
}
func (h *Health) Consuming() bool { return h != nil && h.consuming.Load() }

func (h *Health) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/live", func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/ready", func(writer http.ResponseWriter, _ *http.Request) {
		if h.Consuming() {
			writer.WriteHeader(http.StatusOK)
			return
		}
		writer.WriteHeader(http.StatusServiceUnavailable)
	})
	mux.HandleFunc("/", func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusNotFound)
	})
	return mux
}
