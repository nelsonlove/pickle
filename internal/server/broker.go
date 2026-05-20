package server

import (
	"context"
	"sync"
	"time"

	"github.com/callumalpass/pickle/internal/model"
	"github.com/callumalpass/pickle/internal/store"
)

type Broker struct {
	mu      sync.Mutex
	clients map[chan model.Event]struct{}
	lastID  int64
}

func NewBroker(lastID int64) *Broker {
	return &Broker{
		clients: make(map[chan model.Event]struct{}),
		lastID:  lastID,
	}
}

func (b *Broker) Subscribe() (chan model.Event, func()) {
	ch := make(chan model.Event, 32)
	b.mu.Lock()
	b.clients[ch] = struct{}{}
	b.mu.Unlock()
	return ch, func() {
		b.mu.Lock()
		delete(b.clients, ch)
		close(ch)
		b.mu.Unlock()
	}
}

func (b *Broker) Run(ctx context.Context, s *store.Store) {
	ticker := time.NewTicker(750 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			events, err := s.ListEventsAfter(ctx, b.currentLastID(), 100)
			if err != nil {
				continue
			}
			for _, event := range events {
				b.publish(event)
			}
		}
	}
}

func (b *Broker) currentLastID() int64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.lastID
}

func (b *Broker) publish(event model.Event) {
	b.mu.Lock()
	if event.ID > b.lastID {
		b.lastID = event.ID
	}
	for ch := range b.clients {
		select {
		case ch <- event:
		default:
		}
	}
	b.mu.Unlock()
}
