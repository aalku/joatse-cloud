package org.aalku.joatse.cloud.tools.io;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.function.Consumer;

import org.aalku.joatse.cloud.service.sharing.SharingManager;
import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * A switchboard similar to a socks proxy but targets are not only identified by
 * hostname+port, you need a tag to determine it's context too.
 * 
 * I'ts like "I want to talk to 'Mum'", and then you need a context to know who
 * is 'Mum', who's mum it is.
 * 
 * Connecting to the target is something externally provided to this class. This
 * class responsability is listening to the request, asking an external actor to
 * make the needed connection and finally submitting bytes both ways between the
 * request channel and the target channel until they disconnect.
 *
 * Protocol is:
 * 
 * <pre>
 *  1.    -->  dlong (16 bytes):  tunnel.uuid in binary format.
 *  2.    -->  long  (8 bytes):   targetId.
 *  3.   <--   byte:              0 means connected and any other number would be code for error.
 *  4.A. <-->  bytes:             real traffic, if the byte in the step 3 was a 0.
 *  4.B. <--   ASCII string ending with \0: Error message, if the byte in the step 3 was not a 0. This is optional, the socket might just close instead.
 * </pre>
 * 
 * Error codes:
 * 
 * <pre>
 * 0 = OK.
 * 1 = ... TODO TBD
 * </pre>
 * 
 * There's no need of receiving protocol version since we are talking to the
 * same process. It will always be the same version.
 */
@Component
public class Switchboard implements InitializingBean, DisposableBean, Consumer<Event<Void>>{
	
	private static final Charset ASCII = Charset.forName("ASCII");

	private Logger log = LoggerFactory.getLogger(Switchboard.class);

	@Autowired
	@Qualifier("switchboardPortListener")
	private AsyncTcpPortListener<Void> switchboardPortListener;
	
	@Autowired
	private SharingManager sharingManager; // TODO the tunnel will run through it.

//	@Autowired
//	private TunnelRegistry tunnelRegistry;

	@Override
	public void afterPropertiesSet() throws Exception {
		switchboardPortListener.start(null, this);
		log.info("Switchboard listening at tcp: " + switchboardPortListener.getAddress());
	}
	
	@Override
	public void destroy() throws Exception {
		IOTools.runFailable(()->switchboardPortListener.close());
		log.info("Switchboard closed.");
	}

	@Override
	public void accept(Event<Void> t) {
		if (t.error != null) {
			log.error("Can't accept connections at tcp address: " + switchboardPortListener.getAddress());
		} else if (t.channel != null) {
			handleNewChannel(t.channel);			
		} else {
			log.info("Switchboard listen port was closed");
		}
	}

	private void handleNewChannel(AsynchronousSocketChannel channel) {
		ByteBuffer headerBuffer = ByteBuffer.allocate(16+8);
		IOTools.asyncReadWholeBuffer(channel, headerBuffer).thenAcceptAsync(n->{
			UUID uuid = new UUID(headerBuffer.getLong(), headerBuffer.getLong());
			long targetId = headerBuffer.getLong();
			
			Object context = sharingManager.httpClientEndConnected(uuid, targetId);			
			if (context != null) {
				try {
					IOTools.asyncWriteWholeBuffer(channel, ByteBuffer.wrap(new byte[] {0})) // send 0 = OK
					.thenAccept(x -> {
						try {
							log.info("Switchboard proxy is ready: {}.{}", uuid, targetId);
							sharingManager.httpClientEndConnectionReady(context, channel);
						} catch (Exception e1) {
							reportErrorThenClose(channel, 2, e1.toString());
						}
					}).exceptionally(e->null);
				} catch (Exception e1) {
					reportErrorThenClose(channel, 2, e1.toString());
				}
			} else {
				IOTools.closeChannel(channel);
			}
		}).exceptionally(e->{
			log.error("Switchboard error handling http connection: " + e, e);
			IOTools.closeChannel(channel);
			return null;
		});
	}
	private void reportErrorThenClose(AsynchronousSocketChannel channel, int errorCode, String errorMessage) {
		ByteBuffer bb = ByteBuffer.allocate(errorMessage.length()+2);
		// send errorCode, errorMessage, 0
		bb.put((byte) errorCode).put(errorMessage.getBytes(ASCII)).put((byte) 0);
		bb.flip();
		IOTools.asyncWriteWholeBuffer(channel, bb).handle((n,e)->{
			IOTools.runFailable(()->channel.shutdownOutput());
			return null;
		});
	}
}
