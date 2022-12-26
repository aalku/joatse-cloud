package org.aalku.joatse.cloud.tools.io;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.aalku.joatse.cloud.tools.io.BandwithLimiter.Pause;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BandwithLimiterTest {
	
	private Logger log = LoggerFactory.getLogger(BandwithLimiterTest.class);
	
	@Test
	void fistTest() throws InterruptedException {
		log.info("// fistTest, several windows");
		ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
		BandwithLimiter x = new BandwithLimiter();
		int prec = 100;
		int seconds = 5;
		int objectiveBps = 100000;
		//
		long t0 = System.nanoTime();
		AtomicLong bytes = new AtomicLong(0L);
		try {
			exec.scheduleAtFixedRate(()->{
				int b = objectiveBps / 8 / (1000 / prec);
				bytes.addAndGet(b);
				x.next(b);
			}, 0, 1000 / prec, TimeUnit.MILLISECONDS);
			Thread.sleep(TimeUnit.SECONDS.toMillis(seconds + 1));
		} finally {
			exec.shutdownNow();
		}
		long t1 = System.nanoTime();
		long bps2 = x.calcBps(1).asBps();
		long bps = x.calcBps(4).asBps();
		long bps100 = x.calcBps(100).asBps();
		long timeDeltaMillis = TimeUnit.NANOSECONDS.toMillis(t1 - t0);
		long expected = bytes.get() * 8 * 1000 / timeDeltaMillis;
		log.info("We tried to send {} bits every {} seconds, so {} bps", objectiveBps / prec, 1d / prec, objectiveBps);
		log.info("Sent {} bits in {} seconds, so {} bps", bytes.get() * 8, timeDeltaMillis / 1000d, expected);
		int differencePercent = absDifferencePercent(expected, bps);
		log.info("BandwithLimiter measured {} bps so the difference is < {}%", bps, differencePercent);
		Assertions.assertTrue(differencePercent < 5, "expected differencePercent < 5 but was " + differencePercent);
		int differencePercent2 = absDifferencePercent(expected, bps100);
		log.info("BandwithLimiter (asked for a larger time window) also measured {} bps so the difference is < {}%", bps100, differencePercent2);
		Assertions.assertTrue(differencePercent2 < 5, "expected differencePercent < 5 but was " + differencePercent2);
		int differencePercent3 = absDifferencePercent(expected, bps2);
		log.info("BandwithLimiter (asked for less than 1 time window) also measured {} bps so the difference is < {}%", bps2, differencePercent3);
		Assertions.assertTrue(differencePercent3 < 5, "expected differencePercent < 5 but was " + differencePercent3);
	}
	
	@Test
	void secondTest() throws InterruptedException {
		log.info("// secondTest, less than 1 window");
		BandwithLimiter x = new BandwithLimiter();
		long sleepMillis = 500L; // Much less than 1 window
		int objectiveBps = 1000;
		//
		long bytes = objectiveBps / 8 * sleepMillis / 1000L;
		long t0 = System.nanoTime();
		x.next(bytes);
		Thread.sleep(sleepMillis);
		long t1 = System.nanoTime();
		long bps = x.calcBps(4).asBps();
		long timeDeltaMillis = TimeUnit.NANOSECONDS.toMillis(t1 - t0);
		long expected = bytes * 8 * 1000 / timeDeltaMillis;
		log.info("We tried to send {} bits in {} seconds, so {} bps", objectiveBps * sleepMillis / 1000d, sleepMillis / 1000d, objectiveBps);
		log.info("Sent {} bits in {} seconds, so {} bps", bytes * 8, timeDeltaMillis / 1000d, expected);
		int differencePercent = absDifferencePercent(expected, bps);
		log.info("BandwithLimiter measured {} bps so the difference is < {}%", bps, differencePercent);
		Assertions.assertTrue(differencePercent < 5, "expected differencePercent < 5 but was " + differencePercent);
	}
	
	@Test
	void thirdTest() throws IOException {
		log.info("// thirdTest, pause");
		BandwithLimiter x = new BandwithLimiter();
		int seconds = 5;
		long objectiveBps = 100000;
		long pageMaxBytes = objectiveBps / 8 / 10;
		//
		x.setLimitBps(objectiveBps);
		Random random = new Random();
		long t0 = System.nanoTime();
		AtomicLong bytes = new AtomicLong(0L);
		long st1 = t0 + TimeUnit.SECONDS.toNanos(seconds);
		while (true) {
			if (System.nanoTime() - st1 > 0) {
				break;
			}
			long b = (long) (random.nextDouble() * pageMaxBytes);
			bytes.addAndGet(b);
			Pause pause = x.next(b);
			pause.sleep();
		}
		long t1 = System.nanoTime();
		long bps2 = x.calcBps(1).asBps();
		long bps = x.calcBps(4).asBps();
		long bps100 = x.calcBps(100).asBps();
		long timeDeltaMillis = TimeUnit.NANOSECONDS.toMillis(t1 - t0);
		long expected = bytes.get() * 8 * 1000 / timeDeltaMillis;
		log.info("We tried to send at {} bps", objectiveBps);
		log.info("Sent {} bits in {} seconds, so {} bps", bytes.get() * 8, timeDeltaMillis / 1000d, expected);
		int differencePercent = absDifferencePercent(expected, bps);
		log.info("BandwithLimiter measured {} bps so the difference is < {}%", bps, differencePercent);
		Assertions.assertTrue(differencePercent < 5, "expected differencePercent < 5 but was " + differencePercent);
		int differencePercent2 = absDifferencePercent(expected, bps100);
		log.info("BandwithLimiter (asked for a larger time window) also measured {} bps so the difference is < {}%", bps100, differencePercent2);
		Assertions.assertTrue(differencePercent2 < 5, "expected differencePercent < 5 but was " + differencePercent2);
		int differencePercent3 = absDifferencePercent(expected, bps2);
		log.info("BandwithLimiter (asked for less than 1 time window) also measured {} bps so the difference is < {}%", bps2, differencePercent3);
		Assertions.assertTrue(differencePercent3 < 5, "expected differencePercent < 5 but was " + differencePercent3);
	}

	
	private int absDifferencePercent(long expected, long real) {
		long min = Math.min(expected, real);
		long max = Math.max(expected, real);
		long delta = max - min;
		if (delta == 0) {
			return 0;
		} else if (min == 0) {
			return Integer.MAX_VALUE;
		}
		return (int) (delta * 100 / min);
	}

}
