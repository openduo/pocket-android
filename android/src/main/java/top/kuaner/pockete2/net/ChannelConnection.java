// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.net;

import org.json.JSONObject;

import top.kuaner.pockete2.core.Endpoint;

import java.util.function.BooleanSupplier;

/** HTTP, incremental speech and one live socket, independent of the native bridge. */
public interface ChannelConnection extends ChannelTransport {
    interface LiveListener {
        void text(String text);

        void binary(byte[] bytes);

        void closed(String reason);
    }

    interface StreamListener {
        void data(byte[] bytes);
    }

    Response stream(
            Endpoint endpoint,
            String token,
            long id,
            String path,
            byte[] body,
            StreamListener listener,
            BooleanSupplier current)
            throws Exception;

    void cancelStream(long id);

    void live(
            Endpoint endpoint,
            String token,
            String path,
            JSONObject headers,
            LiveListener listener,
            BooleanSupplier current)
            throws Exception;

    void send(JSONObject frame) throws Exception;

    void sendBinary(byte[] packet) throws Exception;

    boolean isOpen();

    void closeLive();
}
