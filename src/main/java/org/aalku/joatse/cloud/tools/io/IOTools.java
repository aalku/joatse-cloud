package org.aalku.joatse.cloud.tools.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public interface IOTools {
	public interface IOTask<E> {
		E call() throws IOException;
	}
	public interface FailableTask {
		void run() throws Exception;
	}

	public static <X> X runUnchecked(IOTask<X> task) {
		try {
			return task.call();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static <X> boolean runFailable(FailableTask task) {
		try {
			task.run();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static void closeChannel(AsynchronousSocketChannel channel) {
		runFailable(()->channel.shutdownInput());
		runFailable(()->channel.shutdownOutput());
		runFailable(()->channel.close());
	}
	
	public static String toString(ByteBuffer data, int position, int length) {
		StringBuilder sb = new StringBuilder();
		byte[] a = data.array();
		int aStart = data.arrayOffset() + position;
		int aEnd = aStart + length;
		for (int i = aStart; i < aEnd; i++) {
			sb.append(':');
			byte c = a[i];
			sb.append(String.format("%02x", c & 0xFF));
		}
		return sb.toString().substring(1);
	}

	public static String toString(ByteBuffer data) {
		return toString(data, data.position(), data.limit() - data.position());
	}

	/**
	 * Reads until buffer is full or EOF.
	 * 
	 * This call is useful when you need to read an exact ammount of bytes, like a
	 * header or record with defined size and having less bytes is not useful, you
	 * can't use unless you get the whole lot.
	 * 
	 * EOF is handled as an error if the buffer is not full because you don't have
	 * the whole data. EOF after buffer full is ok.
	 * 
	 * Completion value null means ok. If error it will be notified as an
	 * exceptional completion.
	 * 
	 * The very same call will not throw an exception. It would always notify it
	 * through the future result asynchronously.
	 * 
	 * The buffer is not cleared first so if it is half full then only the rest will be used.
	 * 
	 * Buffer returns ready to be read (flipped).
	 */
	public static CompletableFuture<Void> asyncReadWholeBuffer(AsynchronousSocketChannel channel, ByteBuffer buffer) {
		final boolean debug = false;
		final CompletableFuture<Void> res = new CompletableFuture<Void>();
		final Executor executor = ForkJoinPool.commonPool();
		final CompletionHandler<Integer, Void> handler = new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				if (debug) {
					System.err.println("read " + result + " bytes");
				}
				int c = buffer.remaining();
				if (c == 0) {
					buffer.flip();
					res.completeAsync(()->null, executor);
				} else if (result > 0) {
					// Did read some but not enough as c > 0. Read again.
					channel.read(buffer, null, this);
				} else if (result == 0){
					executor.execute(()->res.completeExceptionally(new IOException("EOF before the buffer was complete")));
				} else {
					executor.execute(()->res.completeExceptionally(new IOException("Error while reading. result was < 0: " + result)));
				}
			}
			@Override
			public void failed(Throwable e, Void attachment) {
				executor.execute(()->res.completeExceptionally(e));
			}
		};
		try {
			channel.read(buffer, null, handler);
		} catch (Exception e) {
			executor.execute(()->res.completeExceptionally(e));
		}
		return res;
	}
	
	/**
	 * Writes until buffer is empty
	 * 
	 * Completion value null means ok. If error it will be notified as an
	 * exceptional completion.
	 * 
	 * The very same call will not throw an exception. It would always notify it
	 * through the future result asynchronously.
	 * 
	 * The buffer is not reset first so if it is half full then only the remaining data will be used.
	 * 
	 * Buffer returns ready to be used again (flipped).
	 */
	public static CompletableFuture<Void> asyncWriteWholeBuffer(AsynchronousSocketChannel channel, ByteBuffer buffer) {
		final boolean debug = false;
		final CompletableFuture<Void> res = new CompletableFuture<Void>();
		final Executor executor = ForkJoinPool.commonPool();
		final CompletionHandler<Integer, Void> handler = new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				if (debug) {
					System.err.println("write " + result + " bytes");
				}
				int c = buffer.remaining();
				if (c == 0) {
					buffer.flip();
					res.completeAsync(()->null, executor);
				} else if (result >= 0) {
					// Not enough as c > 0. Write again.
					channel.write(buffer, null, this);
				} else {
					executor.execute(()->res.completeExceptionally(new IOException("Error while writting. result was < 0: " + result)));
				}
			}
			@Override
			public void failed(Throwable e, Void attachment) {
				executor.execute(()->res.completeExceptionally(e));
			}
		};
		try {
			channel.write(buffer, null, handler);
		} catch (Exception e) {
			executor.execute(()->res.completeExceptionally(e));
		}
		return res;
	}

	
	public static void asyncEchoUntilEOF(AsynchronousSocketChannel readFrom, AsynchronousSocketChannel writeTo, int bufferSize) {
		final boolean debug = false;
		ByteBuffer buffer = ByteBuffer.allocate(bufferSize);	
		CompletionHandler<Integer, Boolean> ch = new CompletionHandler<Integer, Boolean>() {
			@Override
			public void completed(Integer result, Boolean reading) {
				if (debug) {
					System.err.println((reading ? "read" : "written") + " " + result + " bytes");
				}
				if (reading) {
					if (result <= 0) {
						try {
							readFrom.shutdownInput();
						} catch (IOException e) {
						}
						try {
							writeTo.shutdownOutput();
						} catch (IOException e) {
						}
					} else {
						buffer.flip();
						if (debug) {
							System.err.println("writting upto " + buffer.remaining() + " bytes...");
						}
						writeTo.write(buffer, false, this);
					}
				} else {
					if (buffer.remaining() > 0) {
						if (debug) {
							System.err.println("writting upto " + buffer.remaining() + " bytes...");
						}
						writeTo.write(buffer, false, this);
					} else {
						buffer.flip();
						if (debug) {
							System.err.println("reading upto " + buffer.remaining() + " bytes...");
						}
						readFrom.read(buffer, true, this);
					}
				}
			}

			@Override
			public void failed(Throwable exc, Boolean reading) {
				try {
					readFrom.close();
				} catch (IOException e) {
				}
				try {
					writeTo.close();
				} catch (IOException e) {
				}
			}
		};
		readFrom.read(buffer, true, ch); // Launch
	}


}