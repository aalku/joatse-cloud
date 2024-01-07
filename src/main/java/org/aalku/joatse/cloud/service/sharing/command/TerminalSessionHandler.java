package org.aalku.joatse.cloud.service.sharing.command;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;

/**
 * A TerminalSessionHandler instance handles the connections of a single JWSSession,
 */
public class TerminalSessionHandler {

	private JWSSession jWSSession;
	
	private ConcurrentHashMap<Long, TerminalConnection> connectionMap = new ConcurrentHashMap<>();

	public interface TerminalUpdateListener {
		public enum Stream { STDOUT, STDERR };
		public static abstract class Event {
			private final Stream stream;	
			public Event(Stream stream) {
				this.stream = stream;
			}
			public Stream getStream() {
				return stream;
			}
		};
		public static class BytesEvent extends Event {
			public final byte[] bytes;
			public BytesEvent(Stream stream, byte[] bytes) {
				super(stream);
				this.bytes = bytes;
			}
		}
		public static class EofEvent extends Event {
			public EofEvent(Stream stream) {
				super(stream);
			}
		}
		public static class CloseEvent extends Event {
			public CloseEvent() {
				super(null);
			}
		}
		public void update(Event bytesEvent);
	}

	public TerminalSessionHandler(JWSSession jWSSession, SharedResourceLot srl) {
		this.jWSSession = jWSSession;
//		this.srl = srl;
	}

	/** New session requested 
	 * @param encryptedSessionHex */
	public Long newSession(CommandTunnel tunnel, TerminalUpdateListener eventHandler, String encryptedSessionHex) {
		TerminalConnection conn = new TerminalConnection(tunnel, eventHandler, jWSSession, encryptedSessionHex);
		this.connectionMap.put(conn.socketId, conn);
		return conn.socketId;
	}

	/** The user has typed in the terminal 
	 * @throws IOException */
	public void type(Long socketId, ByteBuffer encodedText) throws IOException {
		TerminalConnection connection = getConnection(socketId);
		connection.type(encodedText);
	}

	private TerminalConnection getConnection(Long socketId) throws IOException {
		TerminalConnection terminalConnection = connectionMap.get(socketId);
		if (terminalConnection == null) {
			throw new IOException("Connection is closed");
		}
		return terminalConnection;
	}

	/** The user has resized the terminal 
	 * @throws IOException */
	public void resize(Long socketId, int rows, int cols) throws IOException {
		TerminalConnection connection = getConnection(socketId);
		connection.resize(rows, cols);
	}

	/** The the terminal connection has been closed 
	 * @throws IOException */
	public void handleTerminalClosed(Long socketId) throws IOException {
		TerminalConnection connection = getConnection(socketId);
		connection.close(null, false);
	}

}
