package org.aalku.joatse.cloud.tools.io;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Listens on a port and call registered callbacks when a client is connected.
 * 
 * It uses nio2 and does not spare any thread waiting for anything.
 * 
 * Constructor never fails unless callback is null. In case of error the callback will be called.
 * 
 * Context object is for user free use. It's not used internally.
 * 
 */
public class AsyncTcpPortListener<E> {
	
	/** 
	 * 
	 * Event
	 *
	 * Channel will be null in case of error or in case of port close
	 */
	public static class Event<E> {
		public final AsynchronousSocketChannel channel;
		public final Throwable error;
		public final E context;
		public Event(AsynchronousSocketChannel channel, Throwable error, E context) {
			this.channel = channel;
			this.error = error;
			this.context = context;
		}
	}

	private InetSocketAddress address;
	private AsynchronousServerSocketChannel ass;
	private E context;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private Consumer<Event<E>> callback;
	
	public AsyncTcpPortListener(InetAddress address, int port) {
		try {
			try {
				ass = AsynchronousServerSocketChannel.open();
			} catch (IOException e) {
				throw new RuntimeException(e); // Unexpected
			}
			try {
				InetSocketAddress tempAddress = new InetSocketAddress(address, port);
				ass.bind(tempAddress);
				this.address=(InetSocketAddress) ass.getLocalAddress();
			} catch (IOException e) {
				throw new IOException(String.format("Could not listen on port %s:%s: %s", address, port, e), e); // Expected
			}
		} catch (Exception e) {
			// Callback is called in another thread so it doesn't have the inherited context
			// (locks, threadLocals...).
			closed.set(true);
			ForkJoinPool.commonPool().execute(() -> callback.accept(new Event<E>(null, e, context)));
		}
	}
	public AsyncTcpPortListener(InetAddress address, int port, E context, Consumer<AsyncTcpPortListener.Event<E>> callback) {
		this(address, port);
		this.start(context, callback);
	}

	private void close(Throwable e) {
		if (!closed.getAndSet(true)) {
			try {
				ass.close();
			} catch (IOException e1) {
			}
			callback.accept(new Event<E>(null, e, context));
		} else {
			//  ignore
		}
	}

	private void registerAcceptCycle() {
		ass.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
			@Override
			public void failed(Throwable e, Void attachment) {
				close(e);
			}

			@Override
			public void completed(AsynchronousSocketChannel channel, Void attachment) {
				registerAcceptCycle(); // Accept next. This must be the first line.
				callback.accept(new Event<E>(channel, null, context));
			}
		});
	}

	public void close() {
		this.close(null);
	}

	public InetSocketAddress getAddress() {
		return address;
	}

	public void start(E context, Consumer<AsyncTcpPortListener.Event<E>> callback) {
		this.callback = callback;
		this.context = context;
		registerAcceptCycle();		
	}

}
