package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const changeExchange = "chat.realtime.changed.v1"

func consumeChanges(ctx context.Context, endpoint string, clients *hub) {
	for ctx.Err() == nil {
		_ = readChanges(ctx, endpoint, clients)
		if ctx.Err() != nil {
			return
		}
		// Some AMQP connection errors embed the URL. Keep credentials out of logs.
		log.Print("chat realtime broker unavailable, reconnecting")
		select {
		case <-ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}

func readChanges(ctx context.Context, endpoint string, clients *hub) error {
	connection, err := amqp.DialConfig(endpoint, amqp.Config{Dial: amqp.DefaultDial(4 * time.Second)})
	if err != nil {
		return err
	}
	defer connection.Close()
	channel, err := connection.Channel()
	if err != nil {
		return err
	}
	if err := channel.ExchangeDeclare(changeExchange, "fanout", true, false, false, false, nil); err != nil {
		return err
	}
	queue, err := channel.QueueDeclare("", false, true, true, false, amqp.Table{
		"x-message-ttl": int32(15000),
		"x-max-length":  int32(1024),
	})
	if err != nil {
		return err
	}
	if err := channel.QueueBind(queue.Name, "", changeExchange, false, nil); err != nil {
		return err
	}
	deliveries, err := channel.ConsumeWithContext(ctx, queue.Name, "", true, true, false, false, nil)
	if err != nil {
		return err
	}
	clients.setAvailable(true)
	defer clients.setAvailable(false)
	log.Print("chat realtime broker connected")
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case delivery, open := <-deliveries:
			if !open {
				return errors.New("chat broker delivery channel closed")
			}
			var recipients []int64
			if len(delivery.Body) <= 2048 && json.Unmarshal(delivery.Body, &recipients) == nil {
				clients.notify(recipients)
			}
		}
	}
}
