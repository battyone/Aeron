/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.samples;

import uk.co.real_logic.aeron.*;
import uk.co.real_logic.aeron.driver.MediaDriver;
import uk.co.real_logic.aeron.driver.ThreadingMode;
import uk.co.real_logic.aeron.logbuffer.BufferClaim;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.aeron.logbuffer.Header;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static uk.co.real_logic.agrona.UnsafeAccess.UNSAFE;

public class EmbeddedIpcThroughput
{
    public static final int BURST_SIZE = 1_000_000;
    public static final int MESSAGE_LENGTH = SampleConfiguration.MESSAGE_LENGTH;
    public static final int MESSAGE_COUNT_LIMIT = SampleConfiguration.FRAGMENT_COUNT_LIMIT;
    public static final String CHANNEL = CommonContext.IPC_CHANNEL;
    public static final int STREAM_ID = SampleConfiguration.STREAM_ID;
    public static final long IDLE_SLEEP_PERIOD_NS = TimeUnit.MILLISECONDS.toNanos(16);

    public static void main(final String[] args) throws Exception
    {
        if (1 == args.length)
        {
            MediaDriver.loadPropertiesFile(args[0]);
        }

        final AtomicBoolean running = new AtomicBoolean(true);
        SigInt.register(() -> running.set(false));

        final MediaDriver.Context ctx = new MediaDriver.Context()
            .threadingMode(ThreadingMode.DEDICATED)
            .conductorIdleStrategy(new NoOpIdleStrategy())
            .receiverIdleStrategy(new SleepingIdleStrategy(IDLE_SLEEP_PERIOD_NS))
            .senderIdleStrategy(new SleepingIdleStrategy(IDLE_SLEEP_PERIOD_NS));

        final Aeron.Context context = new Aeron.Context();

        try (final MediaDriver ignore = MediaDriver.launch(ctx);
             final Aeron aeron = Aeron.connect(context);
             final Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             final Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID))
        {
            final Subscriber subscriber = new Subscriber(running, subscription);
            final Thread subscriberThread = new Thread(subscriber);
            final Thread publisherThread = new Thread(new Publisher(running, publication));
            final Thread rateReporterThread = new Thread(new RateReporter(running, subscriber));

            rateReporterThread.start();
            subscriberThread.start();
            publisherThread.start();

            publisherThread.join();
            subscriberThread.join();
            rateReporterThread.join();
        }
    }

    public static final class RateReporter implements Runnable
    {
        private final AtomicBoolean running;
        private final Subscriber subscriber;

        public RateReporter(final AtomicBoolean running, final Subscriber subscriber)
        {
            this.running = running;
            this.subscriber = subscriber;
        }

        public void run()
        {
            long lastTimeStamp = System.currentTimeMillis();
            long lastTotalBytes = subscriber.totalBytes();

            while (running.get())
            {
                LockSupport.parkNanos(1_000_000_000);

                final long newTimeStamp = System.currentTimeMillis();
                final long newTotalBytes = subscriber.totalBytes();

                final long duration = newTimeStamp - lastTimeStamp;
                final long bytesTransferred = newTotalBytes - lastTotalBytes;

                System.out.format(
                    "Duration %dms - %,d messages - %,d bytes\n",
                    duration, bytesTransferred / MESSAGE_LENGTH, bytesTransferred);

                lastTimeStamp = newTimeStamp;
                lastTotalBytes = newTotalBytes;
            }
        }
    }

    public static final class Publisher implements Runnable
    {
        private final AtomicBoolean running;
        private final Publication publication;

        public Publisher(final AtomicBoolean running, final Publication publication)
        {
            this.running = running;
            this.publication = publication;
        }

        public void run()
        {
            final Publication publication = this.publication;
            final BufferClaim bufferClaim = new BufferClaim();
            long backPressureCount = 0;
            long totalMessageCount = 0;

            outputResults:
            while (running.get())
            {
                for (int i = 0; i < BURST_SIZE; i++)
                {
                    while (publication.tryClaim(MESSAGE_LENGTH, bufferClaim) <= 0)
                    {
                        ++backPressureCount;
                        if (!running.get())
                        {
                            break outputResults;
                        }
                    }

                    bufferClaim.buffer().putInt(0, i); // Example field write
                    // Real app would write whatever fields are required via a flyweight like SBE

                    bufferClaim.commit();

                    ++totalMessageCount;
                }
            }

            System.out.format("Publisher back pressure ratio: %f\n", (totalMessageCount / (double)backPressureCount));
        }
    }

    public static final class Subscriber implements Runnable, FragmentHandler
    {
        private static final long TOTAL_BYTES_OFFSET;
        static
        {
            try
            {
                TOTAL_BYTES_OFFSET = UNSAFE.objectFieldOffset(Subscriber.class.getDeclaredField("totalBytes"));
            }
            catch (final Exception ex)
            {
                throw new RuntimeException(ex);
            }
        }

        private final AtomicBoolean running;
        private final Subscription subscription;

        private volatile long totalBytes = 0;

        public Subscriber(final AtomicBoolean running, final Subscription subscription)
        {
            this.running = running;
            this.subscription = subscription;
        }

        public long totalBytes()
        {
            return totalBytes;
        }

        public void run()
        {
            while (subscription.images().isEmpty())
            {
                // wait for an image to be ready
                Thread.yield();
            }

            final Image image = subscription.images().get(0);

            while (true)
            {
                final int fragmentsRead = image.poll(this, MESSAGE_COUNT_LIMIT);
                if (0 == fragmentsRead && !running.get())
                {
                    break;
                }
            }
        }

        public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            UNSAFE.putOrderedLong(this, TOTAL_BYTES_OFFSET, totalBytes + length);
        }
    }
}
