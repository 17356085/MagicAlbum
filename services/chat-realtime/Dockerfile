FROM golang:1.26-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -o /out/chat-realtime .

FROM alpine:3.23
COPY --from=build /out/chat-realtime /usr/local/bin/chat-realtime
USER 65532:65532
EXPOSE 8092
ENTRYPOINT ["/usr/local/bin/chat-realtime"]
