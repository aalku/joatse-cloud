package org.aalku.joatse.cloud.tools.io;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BandwithLimiter {
	
	private BandwithLimiter parent;
	
	public static class Pause {

		public static final Pause ZERO = new Pause(0L);
		private long nanosWait;

		public Pause(long nanosWait) {
			this.nanosWait = nanosWait;
		}

		public void sleep() throws IOException {
			try {
				Thread.sleep(TimeUnit.NANOSECONDS.toMillis(nanosWait));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted bandwith limit sleep");
			}
		}

		public Pause orGreater(Pause parentPause) {
			return this.nanosWait >= parentPause.nanosWait ? this : parentPause;
		}
	}
	
	public static class BpsCalc {
		private long bytes;
		private long milliseconds;
		public BpsCalc(long bytes, long milliseconds) {
			this.bytes = bytes;
			this.milliseconds = milliseconds;
		}
		public long asBps() {
			return bytes <= 0L ? 0L : milliseconds <= 0L ? 0L : bytes * 8 * 1000 / milliseconds;
		}
		@Override
		public String toString() {
			return String.format("bpsCalc[%s bytes, %s millis]=%d bps", bytes, milliseconds, asBps());
		}
	}

	private Logger log = LoggerFactory.getLogger(BandwithLimiter.class);
	
	private static class MeasureWindow {
		private final long startTime = System.nanoTime();
		private AtomicReference<Long> endTime = new AtomicReference<>(null);
		private AtomicLong byteCount = new AtomicLong(0L);
		public void update(long byteCount) {
			if (endTime.get() != null) {
				throw new IllegalStateException("Window already closed");
			}
			this.byteCount.addAndGet(byteCount);
		}
		public void close() {
			if (!endTime.compareAndSet(null, System.nanoTime())) {
				throw new IllegalStateException("Window already closed");
			}
		}
		@Override
		public String toString() {
			long nowN = System.nanoTime();
			long nowM = System.currentTimeMillis();
			long bytes = byteCount.get();
			Function<Long, Instant> mapNanos = n->Instant.ofEpochMilli(nowM - TimeUnit.NANOSECONDS.toMillis(nowN - n));
			long end = Optional.ofNullable(endTime.get()).orElse(nowN);
			long deltaMillis = TimeUnit.NANOSECONDS.toMillis(end - startTime);
			return String.format("w[%s --> %s ; b = %d, bps = %s]", mapNanos.apply(startTime), mapNanos.apply(end), bytes, deltaMillis == 0L ? 0 : bytes * 1000 / deltaMillis);
		}
	}
	
	private AtomicLong limitBps = new AtomicLong(0);
	
	private final long windowTimeNanos = TimeUnit.SECONDS.toNanos(2);
	
	private final int windowsStored = (int) (TimeUnit.SECONDS.toNanos(30) / windowTimeNanos);
	
	private LinkedBlockingDeque<MeasureWindow> windows = new LinkedBlockingDeque<>(windowsStored);

	private long maxPauseNanos;

	public Long getLimitBps() {
		return Optional.of(limitBps.get()).filter(n -> n > 0L).orElse(null);
	}

	public void setLimitBps(Long limitBps) {
		this.limitBps.set(Optional.ofNullable(limitBps).orElse(0L));
	}
	
	/**
	 * Count bytes associated to current time window, calc bps and order a pause if
	 * needed
	 */
	public synchronized Pause next(long byteCount) {
		Pause parentPause = parent == null ? Pause.ZERO : parent.next(byteCount);
		final long now = System.nanoTime();
		MeasureWindow last = Optional.ofNullable(windows.peekLast()).filter(w->w.endTime.get() == null).filter(w->{
			if (w.startTime + windowTimeNanos < now) {
				// Too old, close
				w.close();
				return false;
			} else {
				// Suits us
				return true;
			}
		}).orElseGet(()->{
			// No open window. Create one
			MeasureWindow w = new MeasureWindow();
			windows.add(w);
			return w;
		});
		if (last == null || last.endTime.get() != null || last.startTime + windowTimeNanos < now) {
			throw new RuntimeException("Internal error");
		}
		// Sum the bytes to last window
		last.update(byteCount);
		cleanOldWindows(now);
		long limitBps = this.limitBps.get();
		if (limitBps <= 0) {
			// Limit disabled
			return parentPause;
		} else {
			// Limit enabled, so let's calc
			BpsCalc calcBps = calcBps(10);
			long bps = calcBps.asBps();
			long excessBps = bps - limitBps;
			if (excessBps <= 0) {
				return parentPause;
			} else {
				long excessBits = excessBps * calcBps.milliseconds / 1000;
				long nanosToPause = excessBits * TimeUnit.SECONDS.toNanos(1) / limitBps;
				if (log.isDebugEnabled()) {
					log.debug("We transmitted {} bits in the last {} s, so {} bps but the limit is {} so we need to pause {} s", calcBps.bytes * 8,
							calcBps.milliseconds / 1000d, bps, limitBps, nanosToPause / (double) TimeUnit.SECONDS.toNanos(1));
				}
				return new Pause(Math.min(nanosToPause, maxPauseNanos)).orGreater(parentPause);
			}
		}
		
	}

	private void cleanOldWindows(final long now) {
		long timeLimit = now - windowTimeNanos * windowsStored;
		windows.removeIf(w->w.startTime < timeLimit);
	}
	
	public long getMaxSecondsWidth() {
		return TimeUnit.NANOSECONDS.toSeconds(windowTimeNanos * windowsStored);
	}

	public long getCurrentSecondsWidth() {
		return Math.min(getMaxSecondsWidth(),
				TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - windows.getLast().startTime));
	}
	
	public BpsCalc calcBps(int lastSeconds) {
		/*
		 * TODO
		 * 
		 * This algorithm can be improved. If we would need a window and a half then we
		 * use two windows and we give them the same weight. We could give less weight
		 * to windows that are less overlapping the requested time interval.
		 */
		long now = System.nanoTime();
		long timeLimit = now - TimeUnit.SECONDS.toNanos(lastSeconds);
		long bytes = 0;
		long oldestTime = now; // 1 ns is nothing and it prevents 0/0
		Iterator<MeasureWindow> it = windows.descendingIterator();
		int c = 0;
		while (true) {
			MeasureWindow w = it.hasNext() ? w = it.next() : null;
			if (w != null) {
				oldestTime = w.startTime;
				bytes += w.byteCount.get();
				c++;
			}
			if (w == null || w.startTime < timeLimit) {
				// end
				long millis = TimeUnit.NANOSECONDS.toMillis(now - oldestTime);
				if (log.isDebugEnabled()) {
					long bps = bytes <= 0 ? 0 : millis <= 0 ? 0 : bytes * 8 * 1000 / millis;
					log.debug(String.format("bits=%s, s=%.3f, c=%d, bps = %d", bytes * 8, millis / 1000d, c, bps));
				}
				return new BpsCalc(bytes, millis);
			}
		}
	}

	public BandwithLimiter getParent() {
		return parent;
	}

	public void setParent(BandwithLimiter parent) {
		this.parent = parent;
	}

	public void setMaxPauseMillis(long maxPauseMillis) {
		this.maxPauseNanos = TimeUnit.MILLISECONDS.toNanos(maxPauseMillis);
	}
	
}
