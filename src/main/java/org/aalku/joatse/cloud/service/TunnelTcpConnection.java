package org.aalku.joatse.cloud.service;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;

import org.aalku.joatse.cloud.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TunnelTcpConnection extends AbstractToSocketConnection {

	private final Logger log = LoggerFactory.getLogger(TunnelTcpConnection.class);

	private final AsynchronousSocketChannel tcp;

	public TunnelTcpConnection(JWSSession jSession, AsynchronousSocketChannel tcp, long targetId) {
		super(targetId, jSession, null);
		this.tcp = tcp;
	}
	
	/**
	 * Recursively writes all the buffer to tcp.
	 */
	@Override
	protected CompletableFuture<Integer> writeToClient(ByteBuffer buffer) {
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		AsynchronousSocketChannel channel = tcp;
		channel.write(buffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				// log.info("written to tcp: {}", IOTools.toString(buffer, p, result));
				if (buffer.hasRemaining()) {
					writeToClient(buffer) // Recursively write the rest
					.thenAccept(n -> res.complete(n + result)) // Then complete with all the written
					.exceptionally(e -> { // Or fail
						res.completeExceptionally(e);
						return null;
					});
				} else {
					res.complete(result);
				}
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					log.error("tcp write fail because the socket was closed");
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}

	/**
	 *
	 * @param buffer The buffer has no data. We have to clear it and use it.
	 */
	private void tcpToWs(ByteBuffer buffer) {
		tcpRead(buffer).thenAccept(bytesRead->{
			if (bytesRead < 0) {
				close();
				return;
			}
			buffer.flip();
			sendDataMessageToTarget(buffer).whenCompleteAsync((x, e)->{
				if (e != null) {
					close(e, false);
				} else {
					// log.info("CRC32T2W = {}", Integer.toHexString((int)dataCRCT2W.getValue()) );
					tcpToWs(buffer);
				}
			});
		}).exceptionally(e->{
			close(e, false);
			return null;
		});
	}
	
	private CompletableFuture<Integer> tcpRead(ByteBuffer readBuffer) {
		AsynchronousSocketChannel channel = this.tcp;
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		readBuffer.clear();
		channel.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				res.complete(result);
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}

	protected void closeInternal(Throwable e, Boolean remote) {
		IOTools.runFailable(()->tcp.close());
		jSession.remove(this);
	}
	
	public void assertClosed() {
		if (tcp.isOpen()) { // Must be closed
			IOTools.runFailable(()->tcp.close());
			throw new AssertionError("TunnelTcpConnection.JoatseSession.remove(c) assertion error");
		}
	}

	@Override
	protected Void errorConnectingToFinalTarget(Throwable e) {
		log.error("Error connecting to to final target: " + e);
		throw new RuntimeException("Error connecting to to final target: " + e, e);
	}

	@Override
	protected void copyFromClientToTargetForever() {
		tcpToWs(allocateDataBuffer()); // start copying from WS to TCP
	}

	@Override
	protected Logger getLog() {
		return log;
	}
	
}
