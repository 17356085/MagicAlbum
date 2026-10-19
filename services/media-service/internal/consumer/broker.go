package consumer

import (
	"context"
	"errors"
	"net"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	MainExchange       = "media.jobs.v1"
	MainRoutingKey     = "process"
	ProcessQueue       = "media.process.v1"
	RetryExchange      = "media.jobs.retry.v1"
	RetryRoutingKey    = "retry"
	RetryQueue         = "media.retry.v1"
	QuarantineExchange = "media.jobs.dlx.v1"
	QuarantineKey      = "quarantined"
	QuarantineQueue    = "media.quarantine.v1"
)

type Publication struct {
	Exchange   string
	RoutingKey string
	Body       []byte
	Persistent bool
	Mandatory  bool
}

// Delivery is transport-neutral so processing and ACK ordering can be tested
// without a live broker. Body is owned by the broker and must be treated as
// immutable by handlers.
type Delivery struct {
	Body  []byte
	ack   func() error
	nack  func(bool) error
	state *deliveryState
}

type deliveryState struct {
	mu      sync.Mutex
	settled bool
}

func NewDelivery(body []byte, ack func() error, nack func(bool) error) Delivery {
	return Delivery{Body: body, ack: ack, nack: nack, state: &deliveryState{}}
}

func (d *Delivery) Ack() error {
	if d == nil || d.state == nil {
		return errors.New("nil delivery")
	}
	d.state.mu.Lock()
	defer d.state.mu.Unlock()
	if d.state.settled {
		return nil
	}
	if d.ack == nil {
		return errors.New("delivery has no ack callback")
	}
	if err := d.ack(); err != nil {
		return err
	}
	d.state.settled = true
	return nil
}

func (d *Delivery) Nack(requeue bool) error {
	if d == nil || d.state == nil {
		return errors.New("nil delivery")
	}
	d.state.mu.Lock()
	defer d.state.mu.Unlock()
	if d.state.settled {
		return nil
	}
	if d.nack == nil {
		return errors.New("delivery has no nack callback")
	}
	if err := d.nack(requeue); err != nil {
		return err
	}
	d.state.settled = true
	return nil
}

type Session interface {
	Consume(ctx context.Context) (<-chan Delivery, error)
	Publish(ctx context.Context, publication Publication) error
	Close() error
}

type Broker interface {
	Connect(ctx context.Context) (Session, error)
}

// RabbitFactory owns one reconnect attempt. Runtime calls it again after a
// channel/connection failure and topology is rebuilt for every new session.
type RabbitFactory struct {
	URL            string
	ConnectTimeout time.Duration
}

func (f RabbitFactory) Connect(ctx context.Context) (Session, error) {
	if f.URL == "" {
		return nil, errors.New("AMQP URL is empty")
	}
	timeout := f.ConnectTimeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	connectCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var socket net.Conn
	conn, err := amqp.DialConfig(f.URL, amqp.Config{Heartbeat: 10 * time.Second, Locale: "en_US",
		Dial: func(network, address string) (net.Conn, error) {
			connection, dialErr := (&net.Dialer{Timeout: timeout}).DialContext(connectCtx, network, address)
			if dialErr != nil {
				return nil, dialErr
			}
			socket = connection
			deadline, _ := connectCtx.Deadline()
			if dialErr = connection.SetDeadline(deadline); dialErr != nil {
				connection.Close()
				return nil, dialErr
			}
			return connection, nil
		},
	})
	if err != nil {
		return nil, errors.New("AMQP connection failed")
	}
	if ctx.Err() != nil {
		_ = conn.CloseDeadline(time.Now().Add(time.Second))
		return nil, ctx.Err()
	}
	// Bound channel setup RPCs as well as the initial AMQP handshake.
	_ = socket.SetDeadline(time.Now().Add(timeout))
	session, err := newRabbitSession(conn)
	_ = socket.SetDeadline(time.Time{})
	if err != nil {
		_ = conn.CloseDeadline(time.Now().Add(time.Second))
		return nil, err
	}
	session.socket = socket
	return session, nil
}

type rabbitSession struct {
	conn      *amqp.Connection
	socket    net.Conn
	channel   *amqp.Channel
	confirmMu sync.Mutex
	returnCh  chan amqp.Return
	confirmCh chan amqp.Confirmation
	nextTag   uint64
	closeOnce sync.Once
}

func newRabbitSession(conn *amqp.Connection) (*rabbitSession, error) {
	channel, err := conn.Channel()
	if err != nil {
		return nil, errors.New("AMQP channel failed")
	}
	session := &rabbitSession{conn: conn, channel: channel}
	if err := session.declareTopology(); err != nil {
		_ = channel.Close()
		return nil, err
	}
	if err := channel.Confirm(false); err != nil {
		_ = channel.Close()
		return nil, errors.New("AMQP publisher confirms unavailable")
	}
	session.returnCh = channel.NotifyReturn(make(chan amqp.Return, 8))
	session.confirmCh = channel.NotifyPublish(make(chan amqp.Confirmation, 8))
	return session, nil
}

func (s *rabbitSession) declareTopology() error {
	if err := s.channel.ExchangeDeclare(MainExchange, "direct", true, false, false, false, nil); err != nil {
		return errors.New("AMQP main exchange declaration failed")
	}
	if err := s.channel.ExchangeDeclare(RetryExchange, "direct", true, false, false, false, nil); err != nil {
		return errors.New("AMQP retry exchange declaration failed")
	}
	if err := s.channel.ExchangeDeclare(QuarantineExchange, "direct", true, false, false, false, nil); err != nil {
		return errors.New("AMQP quarantine exchange declaration failed")
	}
	if _, err := s.channel.QueueDeclare(ProcessQueue, true, false, false, false, nil); err != nil {
		return errors.New("AMQP process queue declaration failed")
	}
	if _, err := s.channel.QueueDeclare(RetryQueue, true, false, false, false, amqp.Table{
		"x-message-ttl":             int32(10_000),
		"x-dead-letter-exchange":    MainExchange,
		"x-dead-letter-routing-key": MainRoutingKey,
	}); err != nil {
		return errors.New("AMQP retry queue declaration failed")
	}
	if _, err := s.channel.QueueDeclare(QuarantineQueue, true, false, false, false, nil); err != nil {
		return errors.New("AMQP quarantine queue declaration failed")
	}
	if err := s.channel.QueueBind(ProcessQueue, MainRoutingKey, MainExchange, false, nil); err != nil {
		return errors.New("AMQP process binding failed")
	}
	if err := s.channel.QueueBind(RetryQueue, RetryRoutingKey, RetryExchange, false, nil); err != nil {
		return errors.New("AMQP retry binding failed")
	}
	if err := s.channel.QueueBind(QuarantineQueue, QuarantineKey, QuarantineExchange, false, nil); err != nil {
		return errors.New("AMQP quarantine binding failed")
	}
	return nil
}

func (s *rabbitSession) Consume(ctx context.Context) (<-chan Delivery, error) {
	if err := s.channel.Qos(1, 0, false); err != nil {
		return nil, errors.New("AMQP QoS declaration failed")
	}
	messages, err := s.channel.ConsumeWithContext(ctx, ProcessQueue, "media-worker", false, false, false, false, nil)
	if err != nil {
		return nil, errors.New("AMQP consumer declaration failed")
	}
	out := make(chan Delivery)
	go func() {
		defer close(out)
		for message := range messages {
			body := append([]byte(nil), message.Body...)
			delivery := NewDelivery(body, func() error { return message.Ack(false) }, func(requeue bool) error { return message.Nack(false, requeue) })
			select {
			case out <- delivery:
			case <-ctx.Done():
				_ = message.Nack(false, true)
				return
			}
		}
	}()
	return out, nil
}

func (s *rabbitSession) Publish(ctx context.Context, publication Publication) error {
	if publication.Exchange == "" || publication.RoutingKey == "" || len(publication.Body) == 0 {
		return errors.New("invalid AMQP publication")
	}
	s.confirmMu.Lock()
	defer s.confirmMu.Unlock()
	if ctx == nil {
		ctx = context.Background()
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if s.socket != nil {
		deadline, _ := ctx.Deadline()
		_ = s.socket.SetWriteDeadline(deadline)
		defer s.socket.SetWriteDeadline(time.Time{})
	}
	mode := uint8(amqp.Transient)
	if publication.Persistent {
		mode = amqp.Persistent
	}
	s.nextTag++
	expectedTag := s.nextTag
	err := s.channel.PublishWithContext(ctx, publication.Exchange, publication.RoutingKey, publication.Mandatory, false, amqp.Publishing{
		ContentType: "application/json", DeliveryMode: mode, Body: append([]byte(nil), publication.Body...),
	})
	if err != nil {
		return errors.New("AMQP publish failed")
	}
	returned := false
	for {
		select {
		case confirmation, ok := <-s.confirmCh:
			if !ok {
				return errors.New("AMQP confirmations closed")
			}
			if confirmation.DeliveryTag < expectedTag {
				continue
			}
			// RabbitMQ sends basic.return before its confirm. Drain the buffered
			// return even if select chose a simultaneously ready confirmation.
			select {
			case _, ok := <-s.returnCh:
				if !ok {
					return errors.New("AMQP returns closed")
				}
				returned = true
			default:
			}
			if returned || !confirmation.Ack || confirmation.DeliveryTag != expectedTag {
				return errors.New("AMQP publication was rejected")
			}
			return nil
		case _, ok := <-s.returnCh:
			if !ok {
				return errors.New("AMQP returns closed")
			}
			returned = true
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func (s *rabbitSession) Close() error {
	var result error
	s.closeOnce.Do(func() {
		if s.conn != nil {
			result = s.conn.CloseDeadline(time.Now().Add(5 * time.Second))
		}
	})
	return result
}

var _ Broker = RabbitFactory{}
var _ Session = (*rabbitSession)(nil)
