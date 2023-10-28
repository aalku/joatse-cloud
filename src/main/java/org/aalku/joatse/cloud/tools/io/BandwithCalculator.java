package org.aalku.joatse.cloud.tools.io;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public abstract class BandwithCalculator {
	
	private BandwithCalculator() {
		// Do not extend, get an object with from a static method.
	}

	public static class OneWayTraffic {
		private final long bps;
		private final long pps;
		public OneWayTraffic(long bps, long pps) {
			this.bps = bps;
			this.pps = pps;
		}
		public long getBps() {
			return bps;
		}
		public long getPps() {
			return pps;
		}
	}
	
	public static class TwoWayTraffic {
		private final OneWayTraffic in;
		private final OneWayTraffic out;
		public TwoWayTraffic(OneWayTraffic in, OneWayTraffic out) {
			this.in = in;
			this.out = out;
		}
		public OneWayTraffic getIn() {
			return in;
		}
		public OneWayTraffic getOut() {
			return out;
		}
	}

	
	public static class OneWayBandwithCalculator {
		private static final int noTrafficSeconds = 4;
		private static final long nanosPerSecond = TimeUnit.SECONDS.toNanos(1);
		private static final long snapshotEveryNanos = nanosPerSecond * 2;
		private static final long noTrafficNanos = snapshotEveryNanos * noTrafficSeconds;

		private final ReentrantLock writeLock = new ReentrantLock();
		private final AtomicLong totalTraffic = new AtomicLong(0L);		
		private final AtomicLong snapshotTraffic = new AtomicLong(0L);
		private final AtomicLong totalPackets = new AtomicLong(0L);		
		private final AtomicLong snapshotPackets = new AtomicLong(0L);
		private final AtomicLong snapshotNanoTime = new AtomicLong(System.nanoTime());
		private final AtomicLong bps = new AtomicLong(0L);
		private final AtomicLong pps = new AtomicLong(0L);
		private final AtomicReference<OneWayBandwithCalculator> parent = new AtomicReference<>(null);
		public void reportPacket(long bytes) {
			writeLock.lock();
			try {
				/* order might be important */
				long t = System.nanoTime();
				long tp = totalPackets.get();
				long tt = totalTraffic.get();
				long dtimeNanos = t - snapshotNanoTime.get();
				boolean nextSlot = dtimeNanos > snapshotEveryNanos;
				if (nextSlot) {
					long dtraffic = tt - snapshotTraffic.get();
					long dPackets = tp - snapshotPackets.get();
					float dtSeconds = (((float)dtimeNanos) / (float) nanosPerSecond);
					this.bps.set((long) (dtraffic * 8 / dtSeconds));
					this.pps.set((long) (dPackets / dtSeconds));
					snapshotNanoTime.set(t);
					snapshotPackets.set(tp);
					snapshotTraffic.set(tt);
				}
				totalPackets.incrementAndGet();
				totalTraffic.addAndGet(bytes);
			} finally {
				writeLock.unlock();
			}
			Optional.ofNullable(parent.get()).ifPresent(p->p.reportPacket(bytes));
		}
		private void handleNoTraffic() {
			/* order might be important */
			long t = System.nanoTime();
			long sp = snapshotPackets.get();
			long tp = totalPackets.get(); // <- This would be the first to be increased, sp can't be higher
			if (sp == tp) { // Maybe no traffic
				long dtimeNanos = t - snapshotNanoTime.get();
				if (dtimeNanos > noTrafficNanos) {
					// No traffic for 4 seconds. Lock and check again
					writeLock.lock();
					try {
						// check again and snapshot
						t = System.nanoTime();
						dtimeNanos = t - snapshotNanoTime.get();
						tp = totalPackets.get();
						if (sp == tp && dtimeNanos > noTrafficNanos) {
							// No traffic for more than noTrafficNanos
							long tt = totalTraffic.get();
							snapshotNanoTime.set(t);
							snapshotPackets.set(tp);
							snapshotTraffic.set(tt);
						}
					} finally {
						writeLock.unlock();
					}
				}
			}
		}
		public OneWayTraffic getTraffic() {
			handleNoTraffic();
			return new OneWayTraffic(this.bps.get(), this.pps.get());
		}
		public void setParent(OneWayBandwithCalculator parent) {
			this.parent.set(parent);
		}
	}
		
	public static class TwoWayBandwithCalculator {
		private final OneWayBandwithCalculator in = new OneWayBandwithCalculator();
		private final OneWayBandwithCalculator out = new OneWayBandwithCalculator();		
		public void reportPacketIn(long bytes) {
			in.reportPacket(bytes);
		}
		public void reportPacketOut(long bytes) {
			out.reportPacket(bytes);
		}
		public OneWayTraffic getTrafficIn() {
			return in.getTraffic();
		}
		public OneWayTraffic getTrafficOut() {
			return out.getTraffic();
		}
		public void setParent(TwoWayBandwithCalculator parent) {
			in.setParent(parent.in);
			out.setParent(parent.out);
		}
		public OneWayBandwithCalculator getOneWayOut() {
			return out;
		}
		public OneWayBandwithCalculator getOneWayIn() {
			return in;
		}
	}

}
