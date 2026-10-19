package consumer

import (
	"context"
	"errors"
	"time"

	"github.com/17356085/MagicAlbum/services/media-service/internal/telemetry"
	"github.com/17356085/MagicAlbum/services/media-service/internal/wire"
)

type Runtime struct {
	Broker     Broker
	Handler    *Handler
	Health     *Health
	Logger     *telemetry.Logger
	RetryPause time.Duration
}

func (r *Runtime) Run(ctx context.Context) error {
	if ctx == nil {
		ctx = context.Background()
	}
	if r.Broker == nil || r.Handler == nil {
		return errors.New("media runtime requires broker and handler")
	}
	if r.Health == nil {
		r.Health = NewHealth()
	}
	if r.RetryPause <= 0 {
		r.RetryPause = 250 * time.Millisecond
	}
	for {
		if ctx.Err() != nil {
			r.Health.SetConsuming(false)
			return nil
		}
		session, err := r.Broker.Connect(ctx)
		if err != nil {
			r.Health.SetConsuming(false)
			r.log("broker_connect", "retry", 0)
			if !wait(ctx, r.RetryPause) {
				return nil
			}
			continue
		}
		deliveries, err := session.Consume(ctx)
		if err != nil {
			_ = session.Close()
			r.Health.SetConsuming(false)
			r.log("broker_consume", "retry", 0)
			if !wait(ctx, r.RetryPause) {
				return nil
			}
			continue
		}
		r.Health.SetConsuming(true)
		err = r.consumeSession(ctx, session, deliveries)
		r.Health.SetConsuming(false)
		_ = session.Close()
		if ctx.Err() != nil {
			return nil
		}
		if err != nil {
			r.log("broker_session", "retry", 0)
		}
		if !wait(ctx, r.RetryPause) {
			return nil
		}
	}
}

func (r *Runtime) consumeSession(ctx context.Context, session Session, deliveries <-chan Delivery) error {
	for {
		select {
		case <-ctx.Done():
			requeuePending(deliveries)
			return nil
		case delivery, ok := <-deliveries:
			if !ok {
				return errors.New("broker consumer closed")
			}
			started := time.Now()
			outcome := r.Handler.Handle(ctx, delivery.Body)
			if r.Logger != nil {
				event := telemetry.Event{Event: "job_handled", Category: outcome.Reason, Duration: time.Since(started)}
				if envelope, err := wire.ParseEnvelope(delivery.Body); err == nil {
					event.EventID, event.AssetID, event.JobID = envelope.EventID, envelope.AssetID, envelope.JobID
				} else {
					event.Digest = wire.NewQuarantineMessage(delivery.Body, "invalid_message").Digest
				}
				r.Logger.Event(event)
			}
			if ctx.Err() != nil || outcome.Kind == OutcomeShutdown {
				_ = delivery.Nack(true)
				requeuePending(deliveries)
				return nil
			}
			switch outcome.Kind {
			case OutcomeAck:
				if err := delivery.Ack(); err != nil {
					return err
				}
				r.log("message_ack", outcome.Reason, 0)
			case OutcomeRetry:
				if err := r.retryOrRequeue(ctx, session, &delivery); err != nil {
					return err
				}
			case OutcomeQuarantine:
				if err := r.quarantineOrRequeue(ctx, session, &delivery, outcome.Reason); err != nil {
					return err
				}
			default:
				_ = delivery.Nack(true)
				return errors.New("unknown handler outcome")
			}
		}
	}
}

func (r *Runtime) retryOrRequeue(ctx context.Context, session Session, delivery *Delivery) error {
	publication := Publication{Exchange: RetryExchange, RoutingKey: RetryRoutingKey, Body: append([]byte(nil), delivery.Body...), Persistent: true, Mandatory: true}
	if err := session.Publish(ctx, publication); err != nil {
		nackErr := delivery.Nack(true)
		_ = wait(ctx, r.RetryPause)
		if nackErr != nil {
			return nackErr
		}
		return nil
	}
	if err := delivery.Ack(); err != nil {
		return err
	}
	r.log("message_retry", "published", 0)
	return nil
}

func (r *Runtime) quarantineOrRequeue(ctx context.Context, session Session, delivery *Delivery, reason string) error {
	if reason == "" {
		reason = "invalid_message"
	}
	quarantine := wire.NewQuarantineMessage(delivery.Body, reason)
	body, err := quarantine.MarshalJSON()
	if err != nil {
		return delivery.Nack(true)
	}
	publication := Publication{Exchange: QuarantineExchange, RoutingKey: QuarantineKey, Body: body, Persistent: true, Mandatory: true}
	if err := session.Publish(ctx, publication); err != nil {
		nackErr := delivery.Nack(true)
		_ = wait(ctx, r.RetryPause)
		if nackErr != nil {
			return nackErr
		}
		return nil
	}
	if err := delivery.Ack(); err != nil {
		return err
	}
	r.log("message_quarantine", reason, 0)
	return nil
}

func requeuePending(deliveries <-chan Delivery) {
	for {
		select {
		case delivery, ok := <-deliveries:
			if !ok {
				return
			}
			_ = delivery.Nack(true)
		default:
			return
		}
	}
}

func wait(ctx context.Context, duration time.Duration) bool {
	if duration <= 0 {
		return ctx.Err() == nil
	}
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func (r *Runtime) log(event, reason string, duration time.Duration) {
	if r.Logger != nil {
		r.Logger.Event(telemetry.Event{Event: event, Category: reason, Duration: duration})
	}
}
