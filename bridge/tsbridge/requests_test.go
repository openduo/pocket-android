// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package tsbridge

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func beginTestRequest(t *testing.T, id int64) {
	t.Helper()
	if err := RequestBegin(id); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { RequestEnd(id) })
}

func TestRequestRegistrationBoundAndRetirement(t *testing.T) {
	if RequestBegin(0) == nil || RequestBegin(-1) == nil {
		t.Fatal("invalid ids accepted")
	}
	for id := int64(1); id <= 8; id++ {
		beginTestRequest(t, id)
	}
	if RequestBegin(1) == nil || RequestBegin(9) == nil {
		t.Fatal("duplicate or excess group accepted")
	}
	RequestEnd(1)
	RequestEnd(1)
	beginTestRequest(t, 9)
	for id := int64(1); id <= 9; id++ {
		RequestEnd(id)
	}
	requestMu.Lock()
	remaining := len(requestGroups)
	requestMu.Unlock()
	if remaining != 0 {
		t.Fatalf("retired registrations retained: %d", remaining)
	}
}

func TestRetiredRequestCannotStartNodeOrHTTP(t *testing.T) {
	beginTestRequest(t, 20)
	RequestEnd(20)
	// The exported JNI boundary rejects it before asking for a server or choosing any origin.
	_, err := DoAtRequest(20, "example.test", 443, true, "", "POST", "/api/messages", "", nil, 1000)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("retired request: %v", err)
	}
}

func TestRequestCancellationDuringReadiness(t *testing.T) {
	beginTestRequest(t, 21)
	entered := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		_, err := doRequest(21, chanTarget{}, "POST", "/", "", nil, 5000, func(ctx context.Context) error {
			close(entered)
			<-ctx.Done()
			return ctx.Err()
		})
		done <- err
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("readiness did not start")
	}
	RequestEnd(21)
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("readiness survived cancellation")
	}
}

func TestRequestCancellationDuringHeadersAndBody(t *testing.T) {
	for _, flushed := range []bool{false, true} {
		t.Run(map[bool]string{false: "headers", true: "body"}[flushed], func(t *testing.T) {
			entered := make(chan struct{})
			serverEnded := make(chan struct{})
			setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				defer close(serverEnded)
				body, _ := io.ReadAll(r.Body)
				if string(body) != "original bytes" || r.Header.Get("Authorization") != "Bearer qa-scope" || r.Header.Get("Idempotency-Key") != "qa-fixed-key" {
					t.Error("request changed immutable credentials, key or body")
				}
				if flushed {
					w.Write([]byte("partial"))
					w.(http.Flusher).Flush()
				}
				close(entered)
				<-r.Context().Done()
			}))
			beginTestRequest(t, 22)
			target := currentTarget()
			target.Token = "qa-scope"
			done := make(chan error, 1)
			go func() {
				_, err := doRequest(22, target, "POST", "/api/messages", `{"Idempotency-Key":"qa-fixed-key"}`, []byte("original bytes"), 5000, nil)
				done <- err
			}()
			select {
			case <-entered:
			case <-time.After(time.Second):
				t.Fatal("request did not arrive")
			}
			RequestEnd(22)
			select {
			case err := <-done:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("cancelled request returned %v", err)
				}
			case <-time.After(time.Second):
				t.Fatal("request survived retirement")
			}
			select {
			case <-serverEnded:
			case <-time.After(time.Second):
				t.Fatal("server context still live")
			}
		})
	}
}

type requestRoundTrip func(*http.Request) (*http.Response, error)

func (f requestRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestRequestUsesOneDeadlineAcrossReadinessAndHTTP(t *testing.T) {
	beginTestRequest(t, 23)
	var deadline time.Time
	mu.Lock()
	prior := client
	client = &http.Client{Transport: requestRoundTrip(func(r *http.Request) (*http.Response, error) {
		actual, present := r.Context().Deadline()
		if !present || !actual.Equal(deadline) {
			t.Error("HTTP restarted the readiness deadline")
		}
		return &http.Response{StatusCode: 202, Body: io.NopCloser(strings.NewReader("accepted")), Header: make(http.Header)}, nil
	})}
	mu.Unlock()
	t.Cleanup(func() { mu.Lock(); client = prior; mu.Unlock() })
	response, err := doRequest(23, chanTarget{Host: "example.test", Port: 443, TLS: true}, "POST", "/api/messages", "", nil, 1000,
		func(ctx context.Context) error { deadline, _ = ctx.Deadline(); return nil })
	if err != nil || response.Status != 202 {
		t.Fatalf("response=%v, error=%v", response, err)
	}
}

func TestRequestGroupRetainsRetryAndIsolatesTheNextSend(t *testing.T) {
	var calls atomic.Int32
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if string(body) != "frozen" {
			t.Error("retry body changed")
		}
		if calls.Add(1) == 1 {
			w.WriteHeader(503)
		} else {
			w.WriteHeader(202)
		}
	}))
	beginTestRequest(t, 24)
	for _, expected := range []int{503, 202} {
		res, err := doRequest(24, currentTarget(), "POST", "/api/messages", "", []byte("frozen"), 1000, nil)
		if err != nil || res.Status != expected {
			t.Fatalf("retry: %v, %v", res, err)
		}
	}
	RequestEnd(24)
	beginTestRequest(t, 25)
	RequestEnd(24)
	res, err := doRequest(25, currentTarget(), "POST", "/api/messages", "", []byte("frozen"), 1000, nil)
	if err != nil || res.Status != 202 || calls.Load() != 3 {
		t.Fatalf("next send: %v, %v", res, err)
	}
}
