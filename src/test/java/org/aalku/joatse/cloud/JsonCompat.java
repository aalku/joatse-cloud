package org.aalku.joatse.cloud;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;

import org.aalku.joatse.cloud.service.sharing.request.LotSharingRequest;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JsonCompat {
	
	@Test
	final void test1() throws MalformedURLException {
		String msgReq = "{\"request\":\"CONNECTION\",\"httpTunnels\":[{\"targetId\":1697625924213610922,\"targetDescription\":\"Mast\",\"unsafe\":\"false\",\"targetUrl\":\"https://somehost/\"},{\"targetId\":9218226553367314264,\"targetDescription\":\"someName\",\"unsafe\":\"true\",\"targetUrl\":\"https://127.0.0.1:8443/somepath/\"}],\"socks5Tunnel\":[{\"targetId\":2923020727285153231}],\"tcpTunnels\":[{\"targetId\":1618111220495145852,\"targetDescription\":\"someTarget\",\"targetHostname\":\"someHost\",\"targetPort\":22}]}";
//		System.out.println(msgReq);
		JSONObject js = new JSONObject(msgReq);
		LotSharingRequest lotSharingRequest = LotSharingRequest.fromJsonRequest(js, new InetSocketAddress("localhost", 1025));
		js.remove("request");
		js.getJSONArray("httpTunnels").forEach((Object o)->{
			((JSONObject)o).remove("targetId");
		});
		js.getJSONArray("socks5Tunnel").forEach((Object o)->{
			((JSONObject)o).remove("targetId");
		});
		js.getJSONArray("tcpTunnels").forEach((Object o)->{
			((JSONObject)o).remove("targetId");
		});
//		System.out.println(js.toString(0));
		JoatseUser owner = JoatseUser.newLocalUser("admin", false);
		SharedResourceLot sharedResourceLot = new SharedResourceLot(owner, lotSharingRequest, "localhost");
		JSONObject js2 = sharedResourceLot.toJsonSharedResources();
		System.out.println(js2.toString(0));
		Assertions.assertEquals(js.toString(0), js2.toString(0));
	}


}
