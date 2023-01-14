import Header from '/header.js'

const App = {
    el: "#app",
	components: {
		'app-header': Header
	},
    data: {
		sessions:{sessions:[]}
    },
    computed: {
	},
    methods: {
		openHttpTunnel(s, h) {
			window.open(h.listenUrl, "_blank");
			return false;
		}
    },
	async created() {
		let loadData = async ()=> {
	        await fetch('/sessions').then(response => response.json())
        			.then((data) => {
						this.sessions.sessions = data.sessions.map(s=>{
							s.requesterHostname = s.requesterAddress.split(':')[0];
							s.creationTime = new Date(Date.parse(s.creationTime)).toUTCString();
							s.tcpItems = s.tcpItems.map(tcp=>{
								tcp.description = `${tcp.listenHostname}:${tcp.listenPort} --> tunnel --> ${tcp.targetHostname}${tcp.targetPort > 0 ? `:${tcp.targetPort}` : ""}`;
								return tcp;
							});
							s.httpItems = s.httpItems.map(http=>{
								http.description = `${http.listenUrl} --> tunnel --> ${http.targetUrl}`;
								return http;
							});
							return s;
						});
					});
    	}
		await $('.collapse.last').collapse('show');
    	await loadData();
		setInterval(()=>loadData(), 5000);
	}
};
window.addEventListener('load', () => {
  new Vue(App)
})

