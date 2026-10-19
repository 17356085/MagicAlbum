FROM golang:1.26.9-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' -o /out/media-worker ./cmd/media-worker

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/media-worker /usr/local/bin/media-worker
USER nonroot:nonroot
EXPOSE 9091
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 CMD ["/usr/local/bin/media-worker", "--self-health"]
ENTRYPOINT ["/usr/local/bin/media-worker"]
