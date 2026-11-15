// Copyright 2026 openduo and OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.audio;

import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.RustProtocol;

import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Incremental Rust packet owner. The stream worker closes it after completion or cancellation. */
public final class PacketStream implements AutoCloseable {
    public static final int MAX_PACKET_BYTES = 1275;
    private final RustProtocol.Owner owner = new RustProtocol.Owner("packet_stream");

    public List<byte[]> push(byte[] chunk) throws IOException {
        try {
            JSONArray packets = owner.binary(0, chunk).optJSONArray("result");
            List<byte[]> result = new ArrayList<>();
            for (int i = 0; i < packets.length(); i++)
                result.add(RustProtocol.bytes(packets.optJSONArray(i)));
            return result;
        } catch (RustProtocol.Failure error) {
            throw new IOException(error.code, error);
        }
    }

    public int partial() {
        return owner.state().optInt("partial");
    }

    public void validate(byte[] body) throws IOException {
        try {
            owner.binary(3, body);
        } catch (RustProtocol.Failure error) {
            throw new IOException(error.code, error);
        }
    }

    public void finish() throws IOException {
        try {
            owner.dispatch(Json.object("type", "finish"));
        } catch (RustProtocol.Failure error) {
            throw new IOException(error.code, error);
        }
    }

    @Override
    public void close() {
        owner.close();
    }
}
