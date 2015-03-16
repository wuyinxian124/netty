/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.dns;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.net.InetSocketAddress;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Encodes an an {@link AddressedEnvelope} of {@link DnsQuery} into a {@link DatagramPacket}.
 */
@ChannelHandler.Sharable
public class DatagramDnsQueryEncoder extends MessageToMessageEncoder<AddressedEnvelope<DnsQuery, InetSocketAddress>> {

    private final DnsRecordEncoder recordEncoder;

    public DatagramDnsQueryEncoder() {
        this(DnsRecordEncoder.DEFAULT);
    }

    public DatagramDnsQueryEncoder(DnsRecordEncoder recordEncoder) {
        this.recordEncoder = checkNotNull(recordEncoder, "recordEncoder");
    }

    @Override
    protected void encode(
            ChannelHandlerContext ctx,
            AddressedEnvelope<DnsQuery, InetSocketAddress> in, List<Object> out) throws Exception {

        final InetSocketAddress recipient = in.recipient();
        final DnsQuery query = in.content();
        final ByteBuf buf = ctx.alloc().buffer();

        boolean success = false;
        try {
            encodeHeader(query, buf);
            encodeQuestions(query, buf);
            encodeRecords(query, DnsSection.ADDITIONAL, buf);
            success = true;
        } finally {
            if (!success) {
                buf.release();
            }
        }

        out.add(new DatagramPacket(buf, recipient, null));
    }

    /**
     * Encodes the header that is always 12 bytes long.
     *
     * @param query
     *            the query header being encoded
     * @param buf
     *            the buffer the encoded data should be written to
     */
    private static void encodeHeader(DnsQuery query, ByteBuf buf) {
        buf.writeShort(query.id());
        int flags = 0;
        flags |= (query.opCode().byteValue() & 0xFF) << 14;
        flags |= query.isRecursionDesired()? 1 << 8 : 0;
        buf.writeShort(flags);
        buf.writeShort(query.count(DnsSection.QUESTION));
        buf.writeShort(0); // answerCount
        buf.writeShort(0); // authorityResourceCount
        buf.writeShort(query.count(DnsSection.ADDITIONAL));
    }

    private void encodeQuestions(DnsQuery query, ByteBuf buf) throws Exception {
        final int count = query.count(DnsSection.QUESTION);
        for (int i = 0; i < count; i ++) {
            recordEncoder.encodeQuestion((DnsQuestion) query.recordAt(DnsSection.QUESTION, i), buf);
        }
    }

    private void encodeRecords(DnsQuery query, DnsSection section, ByteBuf buf) throws Exception {
        final int count = query.count(section);
        for (int i = 0; i < count; i ++) {
            recordEncoder.encodeRecord(query.recordAt(section, i), buf);
        }
    }
}
