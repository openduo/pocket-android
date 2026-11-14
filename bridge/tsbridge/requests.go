// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package tsbridge

import (
	"context"
	"errors"
	"sync"
	"time"
)

type requestGroup struct {
	ctx    context.Context
	cancel context.CancelFunc
}

var requestMu sync.Mutex
var requestGroups = map[int64]requestGroup{}

// RequestBegin registers an outbox send before native work is queued. Its retries share the id.
func RequestBegin(id int64) error {
	requestMu.Lock()
	defer requestMu.Unlock()
	if id <= 0 {
		return errors.New("tsbridge: invalid request id")
	}
	if _, exists := requestGroups[id]; exists {
		return errors.New("tsbridge: duplicate request id")
	}
	if len(requestGroups) >= 8 {
		return errors.New("tsbridge: too many request groups")
	}
	ctx, cancel := context.WithCancel(context.Background())
	requestGroups[id] = requestGroup{ctx, cancel}
	return nil
}

// RequestEnd prevents a late start and cancels startup, response headers or a response body.
// It removes the registration immediately; repeated retirement needs no cancellation tombstone.
func RequestEnd(id int64) {
	requestMu.Lock()
	group, exists := requestGroups[id]
	delete(requestGroups, id)
	requestMu.Unlock()
	if exists {
		group.cancel()
	}
}

// DoAtRequest uses one deadline for node readiness and the entire HTTP request/response body.
// The immutable origin/token and registered lifetime belong to one outbox operation.
func DoAtRequest(id int64, host string, port int, tls bool, token, method, path, headersJSON string, body []byte, timeoutMs int64) (*Response, error) {
	return doRequest(id, chanTarget{host, port, tls, token}, method, path, headersJSON, body, timeoutMs,
		func(ctx context.Context) error {
			s, err := server()
			if err != nil {
				return err
			}
			_, err = s.Up(ctx)
			return err
		})
}

func doRequest(id int64, t chanTarget, method, path, headersJSON string, body []byte, timeoutMs int64, ready func(context.Context) error) (*Response, error) {
	requestMu.Lock()
	group, exists := requestGroups[id]
	requestMu.Unlock()
	if !exists {
		return nil, context.Canceled
	}
	if timeoutMs <= 0 {
		return nil, context.DeadlineExceeded
	}
	ctx, cancel := context.WithTimeout(group.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if ready != nil {
		if err := ready(ctx); err != nil {
			return nil, err
		}
	}
	return doTargetContext(ctx, t, method, path, headersJSON, body)
}
