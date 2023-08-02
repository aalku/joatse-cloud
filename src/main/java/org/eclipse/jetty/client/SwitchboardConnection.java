package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.EventListener;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SwitchboardConnection extends AbstractConnection {
	/* 
	 * 
	 * TODO Copy org.eclipse.jetty.client.Socks4Proxy 
	 * 
	 */
	static Logger log = LoggerFactory.getLogger(SwitchboardConnection.class);

	private final HttpDestination destination;
	private final EndPoint endPoint;
	private final ByteBuffer response = ByteBuffer.allocate(1024 * 10);
	private Promise<Connection> promise;
	private ClientConnectionFactory connectionFactory;
	private Map<String, Object> context;
	private Executor executor;

	public SwitchboardConnection(EndPoint endPoint, Executor executor, HttpDestination destination,
			Promise<Connection> promise, ClientConnectionFactory connectionFactory, Map<String, Object> context) {
		super(endPoint, executor);
		this.executor = executor;
		this.destination = destination;
		this.endPoint = endPoint;
		this.promise = promise;
        this.connectionFactory = connectionFactory;
        this.context = context;
	}

	@Override
	public void removeEventListener(EventListener listener) {
		log.debug("removeEventListener");
		super.removeEventListener(listener);
	}

	@Override
	public void onOpen() {							
		HttpTunnel tunnel = (HttpTunnel) destination.getOrigin().getTag();
		UUID uuid = tunnel.getTunnel().getUuid();
		log.info("onOpen. HttpTunnel= {} --> {}", uuid, tunnel.getTargetURL().toString());
		super.onOpen();
		// Talk to Switchboard
		ByteBuffer hello = ByteBuffer.allocate(16+8);
		hello.putLong(uuid.getMostSignificantBits());
		hello.putLong(uuid.getLeastSignificantBits());
		hello.putLong(tunnel.getTargetId());
		hello.flip();
		endpointWrite(endPoint, hello) // Write helo
				.thenAccept(n -> fillInterested()) // Interested on a response
				.exceptionally(e -> {
					log.error("Error talking to switchboard: " + e, e);
	                failed(e);
					return null;
				});
	}
	
	static CompletableFuture<Void> endpointWrite(EndPoint endPoint, ByteBuffer buffer) {
		CompletableFuture<Void> res = new CompletableFuture<>();
		endPoint.write(new Callback() {
			@Override
			public void succeeded() {
				res.complete(null);
			}
			@Override
			public void failed(Throwable x) {
				res.completeExceptionally(x);
			}
		}, buffer);
		return res;
	}
	
    public void failed(Throwable x) {
    	log.error("SwitchboardConnection failed: " + x, x);
        close();
        promise.failed(x);
    }

	@Override
	public void onFillable() {
		boolean debug = false;
		ByteBuffer b = ByteBuffer.allocate(1);
		b.limit(0); // flush mode, as they call it
		try {
			int read = getEndPoint().fill(b);
			if (read < 0) {
				throw new IOException("EOF");
			} else if (read == 0) {
				fillInterested();
				return;
			} else {
				byte theByte = b.get();
				if (debug) {
					System.err.println("Received byte " + String.format("0x%02x", theByte & 0xFF));
				}
				response.put(theByte);
				if (response.position() == 1 && theByte == 0) {
					// Success
					tunnel();
				} else if (theByte == 0) {
					// end error, message from position 1 until len -1
					int code = response.get() & 0xFF;
					String msg = new String(response.array(), response.arrayOffset(), response.position() - 2);
					failed(new IOException("Switchboard responded with error " + code + ": " + msg));
				} else {
					fillInterested();
				}
			}
		} catch (IOException e) {
			failed(e);
		}
	}

	private void tunnel() {
        try
        {
            // Don't want to do DNS resolution here.
            InetSocketAddress address = InetSocketAddress.createUnresolved(destination.getHost(), destination.getPort());
            context.put(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY, address);
            ClientConnectionFactory connectionFactory = this.connectionFactory;
            if (destination.isSecure()) {
                HttpClient httpClient = destination.getHttpClient();
				// connectionFactory = httpClient.newSslClientConnectionFactory(null, connectionFactory);
            	connectionFactory = new SslClientConnectionFactory(httpClient.getSslContextFactory(), httpClient.getByteBufferPool(), executor, connectionFactory);
            }
            org.eclipse.jetty.io.Connection newConnection = connectionFactory.newConnection(getEndPoint(), context);
            getEndPoint().upgrade(newConnection);
            log.info("Joatse HTTP tunnel established: {} over {}", this, newConnection);
        }
        catch (Throwable x)
        {
			failed(x);
		}
	}
}