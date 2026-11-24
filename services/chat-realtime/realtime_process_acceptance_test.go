package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/websocket"
)

// TestDualRealtimeRealProcesses is deliberately opt-in through the same
// disposable-broker environment as TestDualRealtimeRealBroker. Unlike the
// in-process test, each hub here is a separately launched service binary.
func TestDualRealtimeRealProcesses(t *testing.T) {
	brokerEndpoint := isolatedBrokerEndpoint(t)
	brokerEnvironment := realtimeProcessBrokerEnvironment(t, brokerEndpoint)
	identityServer := httptest.NewServer(http.HandlerFunc(processIdentityHandler))
	defer identityServer.Close()

	binary := buildRealtimeProcess(t)
	portA := allocateRealtimePort(t)
	portB := allocateRealtimePort(t)
	if portA == portB {
		t.Fatal("allocated the same port for both realtime processes")
	}

	processA := startRealtimeProcess(t, binary, identityServer.URL, portA, brokerEnvironment, "realtime-process-a")
	processB := startRealtimeProcess(t, binary, identityServer.URL, portB, brokerEnvironment, "realtime-process-b")
	waitForRealtimeHealth(t, processA.url, 20*time.Second)
	waitForRealtimeHealth(t, processB.url, 20*time.Second)

	receiverA := dialRealtime(t, processA.url, "receiver-token")
	receiverB := dialRealtime(t, processB.url, "receiver-token")
	unrelated := dialRealtime(t, processA.url, "unrelated-token")
	invalid := dialRealtime(t, processB.url, "invalid-token")
	defer receiverA.Close()
	defer receiverB.Close()
	defer unrelated.Close()
	defer invalid.Close()

	expectRealtimeSignal(t, receiverA, "ready")
	expectRealtimeSignal(t, receiverB, "ready")
	expectRealtimeSignal(t, unrelated, "ready")
	expectRealtimeClosure(t, invalid)

	publishChanged(t, brokerEndpoint, []int64{receiverID})
	expectRealtimeSignal(t, receiverA, "changed")
	expectRealtimeSignal(t, receiverB, "changed")
	expectNoRealtimeSignal(t, unrelated, quietPeriod)

	// Kill only the first OS process. The second process and its client stay
	// live while the first process is replaced on the same isolated port.
	receiverA.Close()
	unrelated.Close()
	stopRealtimeProcess(processA)
	waitForRealtimeHealth(t, processB.url, 5*time.Second)

	processA = startRealtimeProcess(t, binary, identityServer.URL, portA, brokerEnvironment, "realtime-process-a-restarted")
	waitForRealtimeHealth(t, processA.url, 20*time.Second)
	reconnected := dialRealtime(t, processA.url, "receiver-token")
	defer reconnected.Close()
	expectRealtimeSignal(t, reconnected, "ready")

	publishChanged(t, brokerEndpoint, []int64{receiverID})
	expectRealtimeSignal(t, receiverB, "changed")
	expectRealtimeSignal(t, reconnected, "changed")
	expectNoRealtimeSignal(t, receiverB, quietPeriod)
	expectNoRealtimeSignal(t, reconnected, quietPeriod)
}

func processIdentityHandler(response http.ResponseWriter, request *http.Request) {
	if request.URL.Path != "/api/v1/chat/realtime/session" {
		http.NotFound(response, request)
		return
	}
	var identity session
	switch request.Header.Get("Authorization") {
	case "Bearer receiver-token":
		identity.UserID = receiverID
	case "Bearer unrelated-token":
		identity.UserID = unrelatedID
	default:
		http.Error(response, "unauthorized", http.StatusUnauthorized)
		return
	}
	identity.ExpiresAt = time.Now().Add(5 * time.Minute).Unix()
	response.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(response).Encode(identity)
}

func realtimeProcessBrokerEnvironment(t *testing.T, endpoint string) map[string]string {
	t.Helper()
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme != "amqp" || parsed.Host == "" || parsed.User == nil {
		t.Fatalf("process acceptance requires an amqp:// URL with credentials; endpoint=%s", redactProcessEndpoint(endpoint))
	}
	password, hasPassword := parsed.User.Password()
	if !hasPassword {
		t.Fatalf("process acceptance requires a RabbitMQ password; endpoint=%s", redactProcessEndpoint(endpoint))
	}
	port := parsed.Port()
	if port == "" {
		port = "5672"
	}
	vhost := strings.TrimPrefix(parsed.Path, "/")
	if vhost == "" {
		vhost = "/"
	}
	return map[string]string{
		"SPRING_RABBITMQ_HOST":         parsed.Hostname(),
		"SPRING_RABBITMQ_PORT":         port,
		"SPRING_RABBITMQ_USERNAME":     parsed.User.Username(),
		"SPRING_RABBITMQ_PASSWORD":     password,
		"SPRING_RABBITMQ_VIRTUAL_HOST": vhost,
	}
}

func redactProcessEndpoint(endpoint string) string {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return "<invalid>"
	}
	parsed.User = nil
	return parsed.String()
}

func buildRealtimeProcess(t *testing.T) string {
	t.Helper()
	outputPath := filepath.Join(t.TempDir(), "chat-realtime-process")
	if runtime.GOOS == "windows" {
		outputPath += ".exe"
	}
	command := exec.Command("go", "build", "-o", outputPath, ".")
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("build realtime process: %v\n%s", err, output)
	}
	return outputPath
}

func allocateRealtimePort(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("allocate realtime process port: %v", err)
	}
	defer listener.Close()
	return strconv.Itoa(listener.Addr().(*net.TCPAddr).Port)
}

type realtimeProcess struct {
	name    string
	url     string
	command *exec.Cmd
	output  *bytes.Buffer
	stopped bool
}

func startRealtimeProcess(t *testing.T, binary, coreURL, port string, brokerEnvironment map[string]string, name string) *realtimeProcess {
	t.Helper()
	command := exec.Command(binary)
	overrides := make(map[string]string, len(brokerEnvironment)+3)
	for key, value := range brokerEnvironment {
		overrides[key] = value
	}
	overrides["CHAT_REALTIME_CORE_URL"] = coreURL
	overrides["CHAT_REALTIME_ALLOWED_ORIGINS"] = realtimeTestOrigin
	overrides["CHAT_REALTIME_ADDR"] = "127.0.0.1:" + port
	command.Env = realtimeProcessEnvironment(overrides)
	output := &bytes.Buffer{}
	command.Stdout = output
	command.Stderr = output
	if err := command.Start(); err != nil {
		t.Fatalf("start %s: %v", name, err)
	}
	process := &realtimeProcess{
		name:    name,
		url:     "http://127.0.0.1:" + port,
		command: command,
		output:  output,
	}
	t.Cleanup(func() {
		stopRealtimeProcess(process)
		if t.Failed() {
			t.Logf("%s output:\n%s", process.name, process.output.String())
		}
	})
	return process
}

func realtimeProcessEnvironment(overrides map[string]string) []string {
	environment := make([]string, 0, len(os.Environ())+len(overrides))
	for _, entry := range os.Environ() {
		key, _, ok := strings.Cut(entry, "=")
		if !ok {
			continue
		}
		if _, overridden := overrides[key]; overridden {
			continue
		}
		environment = append(environment, entry)
	}
	for key, value := range overrides {
		environment = append(environment, key+"="+value)
	}
	return environment
}

func stopRealtimeProcess(process *realtimeProcess) {
	if process.stopped {
		return
	}
	_ = process.command.Process.Kill()
	_ = process.command.Wait()
	process.stopped = true
}

func expectRealtimeClosure(t *testing.T, connection *websocket.Conn) {
	t.Helper()
	if err := connection.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatalf("set websocket rejection deadline: %v", err)
	}
	var unexpected string
	if err := websocket.Message.Receive(connection, &unexpected); !errors.Is(err, io.EOF) {
		t.Fatalf("invalid credential must close the connection, got frame %q and error %v", unexpected, err)
	}
}
