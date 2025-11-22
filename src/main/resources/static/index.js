import Header from '/header.js'

const App = {
    el: "#app",
	components: {
		'app-header': Header
	},
    data: {
		sessions:{sessions:[], preconfirmedShares:[]},
		showCopiedToClipboard_preconfirmed:false,
		showCopiedToClipboard_preconfirmed2:false,
		showCopiedToClipboard_shareLink:false
    },
    computed: {
	},
    methods: {
		async openHttpTunnel(s, h) {
			await this.allowMyIP(s, false);
			window.open(h.listenUrl, "_blank");
			await this.loadData();
			return false;
		},
		async openFileTunnel(s, f) {
			await this.allowMyIP(s, false);
			window.open(f.listenUrl, "_blank");
			await this.loadData();
			return false;
		},
		async openCommandTunnel(s, item){
			console.log("openCommandTunnel", s.uuid, item.targetId);
			await this.allowMyIP(s, false);
			let url = new URL("/terminal.html", window.location.href);
			url.searchParams.set("uuid", s.uuid);
			url.searchParams.set("targetId", item.targetId);
			let w = window.open(url, "_blank");
			w.joatseTitle = `${item.description} - Joatse`;
		},
		async loadData() {
			let sessions;
		        let pSessions = fetch('/sessions').then(response => response.json())
        			.then((data) => {
						sessions = (data.sessions||[]).map(s=>{
							s.requesterHostname = s.requesterAddress.split(':')[0];
							s.creationTime = new Date(Date.parse(s.creationTime)).toUTCString();
							s.tcpItems = (s.tcpItems||[]).map(tcp=>{
								tcp.description = `${tcp.listenHostname}:${tcp.listenPort} --> tunnel --> ${tcp.targetHostname}${tcp.targetPort > 0 ? `:${tcp.targetPort}` : ""}`;
								return tcp;
							});
							s.httpItems = (s.httpItems||[]).map(http=>{
								http.description = `${http.listenUrl} --> tunnel --> ${http.targetUrl}`;
								return http;
							});
							s.fileItems = (s.fileItems||[]).map(file=>{
								file.description = `${file.listenUrl} --> tunnel --> ${file.targetPath || file.targetDescription || ''}`.trim();
								return file;
							});
							s.commandItems = (s.commandItems||[]).map(cmd=>{
								cmd.description = `${[...cmd.command].join(" ")}`;
								return cmd;
							});
							return s;
						});
					});
			let preconfirmedShares;
	        let pPreconfirmedShares = fetch('/preconfirmedShares').then(response => response.json())
        			.then((data) => {
						preconfirmedShares = data.preconfirmedShares || [];
						let n = 0;
						for (let ps of preconfirmedShares) {
							ps.tcpTunnels = (ps.tcpTunnels||[]).map(tcp=>{
								tcp.description = `${tcp.targetHostname}${tcp.targetPort > 0 ? `:${tcp.targetPort}` : ""}`;
								tcp.targetId = ++n;
								return tcp;
							});
							ps.httpTunnels = (ps.httpTunnels||[]).map(http=>{
								http.description = `${http.targetUrl}`;
								http.targetId = ++n;
								return http;
							});
							if (ps.socks5Tunnel) {
								(ps.tcpTunnels=ps.tunnels||[]).push({targetId:++n, description:"socks5" })
							}
							ps.commandTunnels = (ps.commandTunnels||[]).map(cmd=>{
								cmd.description = `${[...cmd.command].join(" ")}`;
								cmd.targetId = ++n;
								return cmd;
							});
						}
						return data.preconfirmedShares;
					});
			await pSessions;
			await pPreconfirmedShares;
			
			for (let s of preconfirmedShares) {
				s.onLiveSession = false;
			}
			for (let x of sessions) {
    			let preconfirmedSharesSession = await fetch(`/preconfirmedShares?session=${x.uuid}`).then(response => response.json())
    				.then((data) => {
						return data.preconfirmedShares;
					});
				x.preconfirmedShare = preconfirmedSharesSession.length ? preconfirmedSharesSession[0] : null; // Can't be more than one
				if (x.preconfirmedShare) {
					for (let s of preconfirmedShares) {
						if (s.uuid == x.preconfirmedShare.uuid) {
							s.onLiveSession = true;
						}
					}
				}
			}
			this.sessions.sessions = sessions;
			this.sessions.preconfirmedShares = preconfirmedShares;
    	},
		async preconfirm(s) {
			console.log("Saving data...", s);
			let o = { session: s.uuid };
			let res = null;
			let body = null;
			let error = null;
			try {
				res = await fetch('/preconfirmedShares', {
					method: 'PUT',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify(o)
				});
				body = await res.json();
			} catch (e) {
				error = e;
			}
			console.log("Result", res, body, error);
			this.loadData();
		},
		async clearPreconfirm(uuid) {
			console.log("Deleting preconfirm...", uuid);
			let o = { preconfirmed: uuid };
			let res = null;
			let body = null;
			let error = null;
			try {
				res = await fetch('/preconfirmedShares', {
					method: 'DELETE',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify(o)
				});
				body = await res.json();
			} catch (e) {
				error = e;
			}
			this.loadData();
			console.log("Result", res, body, error);
		},
		async allowMyIP(s, reload=true) {
			console.log("Saving data...", s);
			let o = { session: s.uuid };
			let res = null;
			let body = null;
			let error = null;
			try {
				res = await fetch('/allowedIPs', {
					method: 'PUT',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify(o)
				});
				body = await res.json();
			} catch (e) {
				error = e;
			}
			if (reload) {
				await this.loadData();
			}
			console.log("Result", res, body, error);
		},
		copyToClipboard(string, variable) {
			navigator.clipboard.writeText(string);
			this[variable]=true;
			setInterval(()=>this[variable]=false, 1000);
		},
		async disconnectSession(s, reload=true) {
			console.log("Sending data...", s);
			let o = { session: s.uuid };
			let res = null;
			let body = null;
			let error = null;
			try {
				res = await fetch('/sessions', {
					method: 'DELETE',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify(o)
				});
				body = await res.json();
			} catch (e) {
				error = e;
			}
			if (reload) {
				await this.loadData();
			}
			console.log("Result", res, body, error);
		},
    },
	async created() {
		await $('.collapse.last').collapse('show');
    	await this.loadData();
		setInterval(()=>this.loadData(), 5000);
	}
};
window.addEventListener('load', () => {
  new Vue(App)
})

