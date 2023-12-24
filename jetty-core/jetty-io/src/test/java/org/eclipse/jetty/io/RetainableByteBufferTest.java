//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetainableByteBufferTest
{
    public static final int MIN_CAPACITY = 32;
    public static final int MAX_CAPACITY = 64;
    private static ByteBufferPool _pool;

    @BeforeAll
    public static void beforeAll()
    {
        _pool = new ArrayByteBufferPool.Tracking(MIN_CAPACITY, MIN_CAPACITY, MAX_CAPACITY, Integer.MAX_VALUE);
    }

    static Stream<Arguments> buffers()
    {
        return Stream.of(
            Arguments.of(_pool.acquire(MIN_CAPACITY, true)),
            Arguments.of(_pool.acquire(MIN_CAPACITY, false)),
            Arguments.of(RetainableByteBuffer.newAggregator(_pool, true, MIN_CAPACITY, MIN_CAPACITY)),
            Arguments.of(RetainableByteBuffer.newAggregator(_pool, false, MIN_CAPACITY, MIN_CAPACITY)),
            Arguments.of(RetainableByteBuffer.newAccumulator(_pool, true, MIN_CAPACITY)),
            Arguments.of(RetainableByteBuffer.newAccumulator(_pool, false, MIN_CAPACITY))
        );
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testEmptyBuffer(RetainableByteBuffer buffer)
    {
        assertThat(buffer.remaining(), is(0));
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.capacity(), greaterThanOrEqualTo(MIN_CAPACITY));
        assertFalse(buffer.isFull());

        assertThat(buffer.getByteBuffer().remaining(), is(0));
        assertFalse(buffer.getByteBuffer().hasRemaining());
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendOneByte(RetainableByteBuffer buffer)
    {
        byte[] bytes = new byte[] {'-', 'X', '-'};
        while (!buffer.isFull())
            assertThat(buffer.append(bytes, 1, 1), is(1));

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendMoreBytesThanCapacity(RetainableByteBuffer buffer)
    {
        byte[] bytes = new byte[MAX_CAPACITY * 2];
        Arrays.fill(bytes, (byte)'X');
        assertThat(buffer.append(bytes, 0, bytes.length), is(buffer.capacity()));

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        assertTrue(buffer.isFull());
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendSmallByteBuffer(RetainableByteBuffer buffer)
    {
        byte[] bytes = new byte[] {'-', 'X', '-'};
        ByteBuffer from = ByteBuffer.wrap(bytes, 1, 1);
        while (!buffer.isFull())
        {
            ByteBuffer slice = from.slice();
            buffer.append(slice);
            assertFalse(slice.hasRemaining());
        }

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendBigByteBuffer(RetainableByteBuffer buffer)
    {
        ByteBuffer from = BufferUtil.toBuffer("X".repeat(buffer.capacity() * 2));
        buffer.append(from);
        assertTrue(from.hasRemaining());
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        assertTrue(buffer.isFull());
    }
}
