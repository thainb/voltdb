/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.iv2;

import java.util.ArrayDeque;
import java.util.Deque;

import junit.framework.TestCase;

import org.junit.Test;
import org.voltcore.messaging.Mailbox;
import org.voltdb.dtxn.TransactionState;
import org.voltdb.messaging.CompleteTransactionMessage;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.Iv2InitiateTaskMessage;

import static org.mockito.Mockito.*;

public class Iv2TestTransactionTaskQueue extends TestCase
{

    // Cases to test:
    // several single part txns

    private SpProcedureTask createSpProc(long localTxnId)
    {
        Iv2InitiateTaskMessage init = mock(Iv2InitiateTaskMessage.class);
        SpProcedureTask task =
            new SpProcedureTask(null, null, localTxnId, init);
        return task;
    }

    // Create the first fragment of a MP txn
    private FragmentTask createFrag(long localTxnId, long mpTxnId)
    {
        FragmentTaskMessage msg = mock(FragmentTaskMessage.class);
        when(msg.getTxnId()).thenReturn(mpTxnId);
        ParticipantTransactionState pft =
            new ParticipantTransactionState(localTxnId, msg);
        FragmentTask task =
            new FragmentTask(null, pft, msg);
        return task;
    }

    // Create follow-on fragments of an MP txn
    private FragmentTask createFrag(TransactionState txn, long mpTxnId)
    {
        FragmentTaskMessage msg = mock(FragmentTaskMessage.class);
        when(msg.getTxnId()).thenReturn(mpTxnId);
        FragmentTask task =
            new FragmentTask(null, (ParticipantTransactionState)txn, msg);
        return task;
    }

    private CompleteTransactionTask createComplete(TransactionState txn,
                                                   long mpTxnId)
    {
        CompleteTransactionMessage msg = mock(CompleteTransactionMessage.class);
        when(msg.getTxnId()).thenReturn(mpTxnId);
        CompleteTransactionTask task =
            new CompleteTransactionTask(txn, msg);
        return task;
    }

    private void addTask(TransactionTask task, TransactionTaskQueue dut,
                         Deque<TransactionTask> teststorage)
    {
        teststorage.addLast(task);
        dut.offer(task);
    }

    @Test
    public void testBasicParticipantOps() throws InterruptedException
    {
        long localTxnId = 0;
        long mpTxnId = 0;
        SiteTaskerQueue task_queue = new SiteTaskerQueue();
        TransactionTaskQueue dut = new TransactionTaskQueue(task_queue);
        Deque<TransactionTask> expected_order =
            new ArrayDeque<TransactionTask>();

        // add a few SP procs
        TransactionTask next = createSpProc(localTxnId++);
        addTask(next, dut, expected_order);
        next = createSpProc(localTxnId++);
        addTask(next, dut, expected_order);
        next = createSpProc(localTxnId++);
        addTask(next, dut, expected_order);
        // Should squirt on through the queue
        assertEquals(0, dut.size());

        // Now a fragment task to block things
        long blocking_mp_txnid = mpTxnId;
        next = createFrag(localTxnId++, mpTxnId++);
        TransactionTask block = next;
        addTask(next, dut, expected_order);
        assertEquals(1, dut.size());

        // Add some tasks that are going to be blocked
        ArrayDeque<TransactionTask> blocked = new ArrayDeque<TransactionTask>();
        next = createSpProc(localTxnId++);
        addTask(next, dut, blocked);
        next = createSpProc(localTxnId++);
        addTask(next, dut, blocked);

        // here's our next blocker
        long next_blocking_local_txnid = localTxnId;
        long next_blocking_mp_txnid = mpTxnId;
        next = createFrag(localTxnId++, mpTxnId++);
        TransactionTask next_block = next;
        addTask(next, dut, blocked);
        assertEquals(blocked.size() + 1, dut.size());

        // now, do more work on the blocked task
        next = createFrag(block.getTransactionState(), blocking_mp_txnid);
        addTask(next, dut, expected_order);
        // Should have passed through and not be in the queue
        assertEquals(blocked.size() + 1, dut.size());

        // now, complete the blocked task
        next = createComplete(block.getTransactionState(), blocking_mp_txnid);
        addTask(next, dut, expected_order);
        // Should have passed through and not be in the queue
        assertEquals(blocked.size() + 1, dut.size());
        // DONE!  Should flush everything to the next blocker
        block.getTransactionState().setDone();
        while(dut.size() > 1)
        {
            Thread.yield();
        }
        assertEquals(1, dut.size());
        expected_order.addAll(blocked);

        while (!expected_order.isEmpty())
        {
            TransactionTask next_poll = (TransactionTask)task_queue.poll();
            TransactionTask expected = expected_order.removeFirst();
            assertEquals(expected.getLocalTxnId(), next_poll.getLocalTxnId());
            assertEquals(expected.getMpTxnId(), next_poll.getMpTxnId());
        }
    }
}
