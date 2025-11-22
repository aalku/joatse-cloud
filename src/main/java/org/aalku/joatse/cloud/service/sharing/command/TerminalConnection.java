package org.aalku.joatse.cloud.service.sharing.command;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.aalku.joatse.cloud.service.AbstractToSocketConnection;
import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener.BytesEvent;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener.CloseEvent;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener.EofEvent;
import org.aalku.joatse.cloud.service.sharing.command.TerminalSessionHandler.TerminalUpdateListener.Stream;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.codec.Hex;

class TerminalConnection extends AbstractToSocketConnection {

	private static final byte TERM_PROTOCOL_VERSION = 1;

	private static final byte CODE_STDOUT = 1;
	private static final byte CODE_STDERR = 2;
	
	private static final byte CODE_TYPE = 1;
	private static final byte CODE_RESIZE = 2;

	private static final Logger log = LoggerFactory.getLogger(TerminalConnection.class);

	
//	private final CommandTunnel tunnel;
	private final TerminalUpdateListener terminalUpdateHandler;

	private volatile boolean closed = false;

	public TerminalConnection(CommandTunnel tunnel, TerminalUpdateListener terminalUpdateHandler, JWSSession jSession, String encryptedSessionHex) {
		super(tunnel.getTargetId(), jSession, ByteBuffer.wrap(Hex.decode(encryptedSessionHex)));
//		this.tunnel = tunnel;
		this.terminalUpdateHandler = terminalUpdateHandler;
	}
	
	@Override
	protected Logger getLog() {
		return log;
	}

	@Override
	protected void copyFromClientToTargetForever() {
		/*
		 * This is not needed in TerminalConnection. Messages from client to target will
		 * be sent in a different way.
		 */
	}

	@Override
	protected void closeInternal(Throwable e, Boolean b) {
		synchronized (terminalUpdateHandler) {
			IOTools.runFailable(()->{
				terminalUpdateHandler.update(new EofEvent(Stream.STDOUT));
				terminalUpdateHandler.update(new EofEvent(Stream.STDERR));
				terminalUpdateHandler.update(new CloseEvent());
			});
			closed = true;
		}
	}

	@Override
	protected Void errorConnectingToFinalTarget(Throwable e) {
		synchronized (terminalUpdateHandler) {
			throw new RuntimeException("Error connecting to to final target: " + e, e);
		}
	}
	
	@Override
	protected CompletableFuture<Integer> writeToClient(ByteBuffer buffer) {
		synchronized (terminalUpdateHandler) {
			int version = buffer.get();
			if (version != TERM_PROTOCOL_VERSION) {
				IOException ex = new IOException("Unsupported Terminal protocol version: " + version);
				return CompletableFuture.failedFuture(ex);
			}
			byte code = buffer.get();
			if (code == CODE_STDOUT || code == CODE_STDERR) {
				Stream stream = code == CODE_STDOUT ? TerminalUpdateListener.Stream.STDOUT
						: TerminalUpdateListener.Stream.STDERR;
				byte[] dest = new byte[buffer.remaining()];
				if (dest.length == 0) {
					terminalUpdateHandler.update(new EofEvent(stream));
				} else {
					buffer.get(dest);
					terminalUpdateHandler.update(new BytesEvent(stream, dest));
				}
				return CompletableFuture.completedFuture(dest.length + 1);
			}
			IllegalStateException ex = new IllegalStateException("Unexpected message code: " + (code & 0xFF));
			close(ex, false);
			return CompletableFuture.failedFuture(ex);
		}
	}

	public void type(ByteBuffer encodedText) {
		int headerLen = 1 + 1 + 4;
		ByteBuffer buffer2 = ByteBuffer.allocate(encodedText.limit()+headerLen);
		buffer2.put(TERM_PROTOCOL_VERSION);
		buffer2.put(CODE_TYPE);
		buffer2.putInt(encodedText.remaining());
		buffer2.put(encodedText);
		buffer2.flip();
//		log.info("message: {}", IOTools.toString(buffer2));
		sendDataMessageToTarget(buffer2);
	}

	public void resize(int rows, int cols) {
		int headerLen = 1 + 1 + 4;
		ByteBuffer buffer = ByteBuffer.allocate(headerLen + 8);
		buffer.put(TERM_PROTOCOL_VERSION);
		buffer.put(CODE_RESIZE);
		buffer.putInt(rows);
		buffer.putInt(cols);
		buffer.flip();
//		log.info("message: {}", IOTools.toString(buffer));
		sendDataMessageToTarget(buffer);
	}

	@Override
	protected void assertClosed() {
		synchronized (terminalUpdateHandler) {
			if (!closed) { // Must be closed
				AssertionError e = new AssertionError("AssertionError");
				getLog().error("assertClosed error: {}", e);
				throw e;
			}
		}
	}

}