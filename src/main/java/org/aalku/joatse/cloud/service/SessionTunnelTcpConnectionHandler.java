package org.aalku.joatse.cloud.service;

import java.nio.channels.AsynchronousSocketChannel;
import java.util.function.BiConsumer;

import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SessionTunnelTcpConnectionHandler implements BiConsumer<Long, AsynchronousSocketChannel> {

	private Logger log = LoggerFactory.getLogger(SessionTunnelTcpConnectionHandler.class);

	private final JWSSession jWSSession;
	private final SharedResourceLot srl;

	SessionTunnelTcpConnectionHandler(JWSSession jWSSession, SharedResourceLot srl) {
		this.jWSSession = jWSSession;
		this.srl = srl;
	}

	@Override
	public void accept(Long targetId, AsynchronousSocketChannel t) {
		log.info("Connection arrived from {} for tunnel {}.{} !!", IOTools.runUnchecked(()->t.getRemoteAddress()), srl.getUuid(), targetId);
		TcpTunnel tcpItem = srl.getTcpItem(targetId);
		HttpTunnel httpTunnel = srl.getHttpItem(targetId);
		if (tcpItem == null && httpTunnel == null) {
			log.error("Unknown targetId: " + targetId);
			IOTools.runFailable(()->t.close());
			return;
		}
		TunnelTcpConnection c = new TunnelTcpConnection(jWSSession, t, targetId);
		c.getCloseStatus().thenAccept(remote->{
			// Connection closed ok
			if (remote == null) {
				log.info("TCP tunnel closed");
			} else if (remote) {
				log.info("TCP tunnel closed by target side");
			} else {
				log.info("TCP tunnel closed by this side");
			}
		}).exceptionally(e->{
			log.error("TCP tunnel closed because of error: {}", e, e);
			return null;
		});
	}
}