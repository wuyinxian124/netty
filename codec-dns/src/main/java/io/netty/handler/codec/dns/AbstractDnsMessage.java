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

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.ResourceLeak;
import io.netty.util.ResourceLeakDetector;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * A skeletal implementation of {@link DnsMessage}.
 */
public abstract class AbstractDnsMessage extends AbstractReferenceCounted implements DnsMessage {

    private static final ResourceLeakDetector<DnsMessage> leakDetector =
            new ResourceLeakDetector<DnsMessage>(DnsMessage.class);

    private static final int SECTION_QUESTION = DnsSection.QUESTION.ordinal();

    private final ResourceLeak leak = leakDetector.open(this);
    private short id;
    private DnsOpCode opCode;
    private boolean recursionDesired;
    private byte z;

    private final Object[] sections = new Object[4]; // Each element is a single record or the list of records.

    /**
     * Creates a new instance with the specified {@code id} and {@link DnsOpCode#QUERY} opCode.
     */
    protected AbstractDnsMessage(int id) {
        this(id, DnsOpCode.QUERY);
    }

    /**
     * Creates a new instance with the specified {@code id} and {@code opCode}.
     */
    protected AbstractDnsMessage(int id, DnsOpCode opCode) {
        setId(id);
        setOpCode(opCode);
    }

    @Override
    public int id() {
        return id & 0xFFFF;
    }

    @Override
    public DnsMessage setId(int id) {
        this.id = (short) id;
        return this;
    }

    @Override
    public DnsOpCode opCode() {
        return opCode;
    }

    @Override
    public DnsMessage setOpCode(DnsOpCode opCode) {
        this.opCode = checkNotNull(opCode, "opCode");
        return this;
    }

    @Override
    public boolean isRecursionDesired() {
        return recursionDesired;
    }

    @Override
    public DnsMessage setRecursionDesired(boolean recursionDesired) {
        this.recursionDesired = recursionDesired;
        return this;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public DnsMessage setZ(int z) {
        this.z = (byte) (z & 7);
        return this;
    }

    @Override
    public int count(DnsSection section) {
        return count(sectionOrdinal(section));
    }

    private int count(int section) {
        final Object records = sections[section];
        if (records == null) {
            return 0;
        }
        if (records instanceof DnsRecord) {
            return 1;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        return recordList.size();
    }

    @Override
    public int count() {
        int count = 0;
        for (int i = 0; i < sections.length; i ++) {
            count += count(i);
        }
        return count;
    }

    @Override
    public <T extends DnsRecord> T recordAt(DnsSection section) {
        return recordAt(sectionOrdinal(section));
    }

    private <T extends DnsRecord> T recordAt(int section) {
        final Object records = sections[section];
        if (records == null) {
            return null;
        }

        if (records instanceof DnsRecord) {
            return castRecord(records);
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        if (recordList.isEmpty()) {
            return null;
        }

        return castRecord(recordList.get(0));
    }

    @Override
    public <T extends DnsRecord> T recordAt(DnsSection section, int index) {
        return recordAt(sectionOrdinal(section), index);
    }

    private <T extends DnsRecord> T recordAt(int section, int index) {
        final Object records = sections[section];
        if (records == null) {
            throw new IndexOutOfBoundsException(String.valueOf(index));
        }

        if (records instanceof DnsRecord) {
            if (index == 0) {
                return castRecord(records);
            } else {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        return castRecord(recordList.get(index));
    }

    @Override
    public DnsMessage setRecord(DnsSection section, DnsRecord record) {
        setRecord(sectionOrdinal(section), record);
        return this;
    }

    private void setRecord(int section, DnsRecord record) {
        sections[section] = checkQuestion(section, record);
    }

    @Override
    public DnsMessage setRecord(DnsSection section, int index, DnsRecord record) {
        setRecord(sectionOrdinal(section), index, record);
        return this;
    }

    private void setRecord(int section, int index, DnsRecord record) {
        checkQuestion(section, record);

        final Object records = sections[section];
        if (records == null) {
            throw new IndexOutOfBoundsException(String.valueOf(index));
        }

        if (records instanceof DnsRecord) {
            if (index == 0) {
                sections[section] = record;
            } else {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        recordList.set(index, record);
    }

    @Override
    public DnsMessage addRecord(DnsSection section, DnsRecord record) {
        addRecord(sectionOrdinal(section), record);
        return this;
    }

    private void addRecord(int section, DnsRecord record) {
        checkQuestion(section, record);

        final Object records = sections[section];
        if (records == null) {
            sections[section] = record;
            return;
        }

        if (records instanceof DnsRecord) {
            final List<DnsRecord> recordList = new ArrayList<DnsRecord>(2);
            recordList.add(castRecord(records));
            recordList.add(record);
            sections[section] = recordList;
            return;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        recordList.add(record);
    }

    @Override
    public DnsMessage addRecord(DnsSection section, int index, DnsRecord record) {
        addRecord(sectionOrdinal(section), index, record);
        return this;
    }

    private void addRecord(int section, int index, DnsRecord record) {
        checkQuestion(section, record);

        final Object records = sections[section];
        if (records == null) {
            if (index != 0) {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }

            sections[section] = record;
            return;
        }

        if (records instanceof DnsRecord) {
            final List<DnsRecord> recordList;
            if (index == 0) {
                recordList = new ArrayList<DnsRecord>(2);
                recordList.add(record);
                recordList.add(castRecord(records));
            } else if (index == 1) {
                recordList = new ArrayList<DnsRecord>(2);
                recordList.add(castRecord(records));
                recordList.add(record);
            } else {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }
            sections[section] = recordList;
            return;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        recordList.add(index, record);
    }

    @Override
    public <T extends DnsRecord> T removeRecord(DnsSection section, int index) {
        return removeRecord(sectionOrdinal(section), index);
    }

    private <T extends DnsRecord> T removeRecord(int section, int index) {
        final Object records = sections[section];
        if (records == null) {
            throw new IndexOutOfBoundsException(String.valueOf(index));
        }

        if (records instanceof DnsRecord) {
            if (index != 0) {
                throw new IndexOutOfBoundsException(String.valueOf(index));
            }

            T record = castRecord(records);
            sections[section] = null;
            return record;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        return castRecord(recordList.remove(index));
    }

    @Override
    public DnsMessage clear(DnsSection section) {
        clear(sectionOrdinal(section));
        return this;
    }

    @Override
    public DnsMessage clear() {
        for (int i = 0; i < sections.length; i ++) {
            clear(i);
        }
        return this;
    }

    private void clear(int section) {
        final Object recordOrList = sections[section];
        if (recordOrList != null) {
            if (recordOrList instanceof ReferenceCounted) {
                ((ReferenceCounted) recordOrList).release();
            } else if (recordOrList instanceof List) {
                @SuppressWarnings("unchecked")
                List<DnsRecord> list = (List<DnsRecord>) recordOrList;
                if (!list.isEmpty()) {
                    for (Object r : list) {
                        ReferenceCountUtil.release(r);
                    }
                }
            }
        }
    }

    @Override
    public DnsMessage touch() {
        return (DnsMessage) super.touch();
    }

    @Override
    public DnsMessage touch(Object hint) {
        if (leak != null) {
            leak.record(hint);
        }
        return this;
    }

    @Override
    public DnsMessage retain() {
        return (DnsMessage) super.retain();
    }

    @Override
    public DnsMessage retain(int increment) {
        return (DnsMessage) super.retain(increment);
    }

    @Override
    protected void deallocate() {
        clear();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DnsMessage)) {
            return false;
        }

        final DnsMessage that = (DnsMessage) obj;
        if (id() != that.id()) {
            return false;
        }

        if (this instanceof DnsQuery) {
            if (!(that instanceof DnsQuery)) {
                return false;
            }
        } else if (that instanceof DnsQuery) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return id() * 31 + (this instanceof DnsQuery? 0 : 1);
    }

    private static int sectionOrdinal(DnsSection section) {
        return checkNotNull(section, "section").ordinal();
    }

    private static DnsRecord checkQuestion(int section, DnsRecord record) {
        if (section == SECTION_QUESTION && !(checkNotNull(record, "record") instanceof DnsQuestion)) {
            throw new IllegalArgumentException(
                    "record: " + record + " (expected: " + DnsQuestion.class.getSimpleName() + ')');
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private static <T extends DnsRecord> T castRecord(Object record) {
        return (T) record;
    }
}
