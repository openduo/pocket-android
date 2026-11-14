// Copyright 2026 openduo
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package tsbridge

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type chunks struct {
	mu  sync.Mutex
	got bytes.Buffer
	n   int
	on  func()
}

func (c *chunks) OnStreamData(data []byte) {
	c.mu.Lock()
	c.got.Write(data)
	c.n++
	on := c.on
	c.mu.Unlock()
	if on != nil {
		on()
	}
}

func TestStreamDeliversTheBodyAsItArrives(t *testing.T) {
	release := make(chan struct{})
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/speech" || r.Method != "POST" || r.Header.Get("Content-Type") != "application/json" {
			http.Error(w, "unexpected", 400)
			return
		}
		w.WriteHeader(200)
		w.Write([]byte{1, 0, 9})
		w.(http.Flusher).Flush()
		<-release
		w.Write([]byte{1, 0, 8})
	}))
	h := &chunks{}
	h.on = func() {
		h.mu.Lock()
		first := h.n == 1
		h.mu.Unlock()
		// The first chunk arrived before the server wrote the second.
		if first {
			close(release)
		}
	}
	res, err := Stream(1, "POST", "/api/speech?room=r", `{"Content-Type":"application/json"}`, []byte(`{"text":"x"}`), h)
	if err != nil || res.Status != 200 {
		t.Fatalf("res=%v err=%v", res, err)
	}
	if !bytes.Equal(h.got.Bytes(), []byte{1, 0, 9, 1, 0, 8}) {
		t.Fatalf("body %v", h.got.Bytes())
	}
}

func TestStreamErrorStatusReturnsItsBody(t *testing.T) {
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(422)
		w.Write([]byte(`{"error":"nothing_to_speak"}`))
	}))
	h := &chunks{}
	res, err := Stream(2, "POST", "/api/speech", "", nil, h)
	if err != nil || res.Status != 422 || string(res.Body) != `{"error":"nothing_to_speak"}` || h.n != 0 {
		t.Fatalf("res=%v err=%v chunks=%d", res, err, h.n)
	}
}

func TestStreamCutBodyIsAnError(t *testing.T) {
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.Write([]byte{5, 0, 1})
		w.(http.Flusher).Flush()
		// Destroy the response mid-body, as the channel does when synthesis fails after the
		// first packet.
		conn, _, _ := w.(http.Hijacker).Hijack()
		conn.Close()
	}))
	if _, err := Stream(3, "POST", "/api/speech", "", nil, &chunks{}); err == nil {
		t.Fatal("a cut body returned no error")
	}
}

func TestStreamCancelEndsTheRequest(t *testing.T) {
	block := make(chan struct{})
	t.Cleanup(func() { close(block) })
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.Write([]byte{1, 0, 1})
		w.(http.Flusher).Flush()
		select {
		case <-block:
		case <-r.Context().Done():
		}
	}))
	h := &chunks{}
	h.on = func() { StreamCancel(4) }
	if _, err := Stream(4, "POST", "/api/speech", "", nil, h); err == nil {
		t.Fatal("a cancelled stream returned no error")
	}
}

func TestStreamCancelledBeforeItStartsIsNotSent(t *testing.T) {
	sent := false
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { sent = true }))
	StreamCancel(5)
	if _, err := Stream(5, "POST", "/api/speech", "", nil, &chunks{}); err == nil || sent {
		t.Fatalf("err=%v sent=%v", err, sent)
	}
}

func TestScopedStreamRetirementDoesNotKeepTombstones(t *testing.T) {
	var sent atomic.Int32
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sent.Add(1)
		w.Write([]byte{1, 0, 9})
	}))
	target := currentTarget()
	stream := func(id int64, handler StreamHandler) (*Response, error) {
		return StreamAtRequest(id, target.Host, target.Port, target.TLS, target.Token,
			"POST", "/api/speech", "", nil, handler)
	}
	if err := RequestBegin(810); err != nil {
		t.Fatal(err)
	}
	RequestEnd(810)
	if _, err := stream(810, &chunks{}); !errors.Is(err, context.Canceled) || sent.Load() != 0 {
		t.Fatalf("retired stream: err=%v sent=%d", err, sent.Load())
	}
	if err := RequestBegin(811); err != nil {
		t.Fatal(err)
	}
	defer RequestEnd(811)
	if res, err := stream(811, &chunks{}); err != nil || res.Status != 200 || sent.Load() != 1 {
		t.Fatalf("replacement: res=%v err=%v sent=%d", res, err, sent.Load())
	}
	RequestEnd(811)
	RequestEnd(811)
	streamMu.Lock()
	tombstones := len(streamCancelled)
	streamMu.Unlock()
	requestMu.Lock()
	groups := len(requestGroups)
	requestMu.Unlock()
	if tombstones != 0 || groups != 0 {
		t.Fatalf("retirement retained groups=%d tombstones=%d", groups, tombstones)
	}
}

func TestScopedStreamRetirementCancelsBody(t *testing.T) {
	setupUpstream(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte{1, 0, 9})
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	if err := RequestBegin(812); err != nil {
		t.Fatal(err)
	}
	defer RequestEnd(812)
	h := &chunks{on: func() { RequestEnd(812) }}
	target := currentTarget()
	done := make(chan error, 1)
	go func() {
		_, err := StreamAtRequest(812, target.Host, target.Port, target.TLS, target.Token,
			"POST", "/api/speech", "", nil, h)
		done <- err
	}()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled stream completed successfully")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("retired streaming body did not stop")
	}
}
