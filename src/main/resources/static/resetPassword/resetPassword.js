import Header from '/header.js'

const App = {
	el: "#app",
	components: {
		'app-header': Header
	},
	data: {
		email: "",
		emailSentTo: "",
		emailOk: false,
		password: "",
		password2: "",
		emailError: "",
		passwordOk: false,
		passwordError: "",
		tokenError: "",
	},
	computed: {
		emailState() {
			return !!this.email?.match(/^(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|"(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21\x23-\x5b\x5d-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])*")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21-\x5a\x53-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])+)\])$/);
		},
		emailSent() {
			return this.emailSentTo == this.email;
		},
		passwordState() {
			return !!this.password?.match(/^\S.{6,}\S$/);
		},
		passwordConfirmState() {
			return this.passwordState && this.password == this.password2;
		},
		pathname() {
			return new URL(window.location.href).pathname;
		},
		token() {
			return new URL(window.location.href).searchParams.get("token");
		},
		emailInUri() {
			return new URL(window.location.href).searchParams.get("email");
		}
	},
	methods: {
		async submitEmail() {
			let email = this.email;
			this.emailOk = false;
			this.emailError = "";
			console.debug("submitEmail", email);
			this.emailSentTo = this.email;
			try {
				let res = await fetch('/resetPassword', {
					method: 'POST',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify({ email }),
				}).then(r=>r.json());
				console.debug("submitEmail", "response", res);
				if (res?.result == "success") {
					this.emailOk = true;
				} else {
					this.emailError = res?.msg || "Error sending email";
				}
			} catch (e) {
				console.error(e);
				this.emailError = e || "Error sending email";
			}
		},
		async submitPassword() {
			console.debug("submitPassword", this.password.length, this.passwordConfirmState);
			let password = this.password;
			let token = this.token;
			let email = this.email;
			try {
				let res = await fetch('/resetPassword', {
					method: 'POST',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify({ token, email, password }),
				}).then(r=>r.json());
				console.debug("submitPassword", "response", res);
				if (res?.result == "success") {
					this.passwordOk = true;
				} else {
					this.passwordError = res?.msg || "Error changing password";
				}
			} catch (e) {
				console.error(e);
				this.passwordError = e || "Error changing password";
			}
		},
	},
	async created() {
		let loadData = async () => {
		}
		await loadData();
		setInterval(() => loadData(), 5000);
		
		if (this.token) {
			let token = this.token;
			try {
				let res = await fetch('/resetPassword', {
					method: 'POST',
					headers: {
						'Content-type': 'application/json; charset=UTF-8',
					},
					body: JSON.stringify({ token }),
				}).then(r=>r.json());
				console.debug("submitToken", "response", res);
				if (res?.result == "success") {
					this.email = res.username;
				} else {
					this.tokenError = "There was an error with this request. Maybe the link was too old.";
				}
			} catch (e) {
				console.error(e);
					this.tokenError = "There was an error with this request. Maybe the link was too old.";
			}
		} else if (this.emailInUri) {
			this.email = this.emailInUri;
		}
	}
};


window.addEventListener('load', () => {
  new Vue(App)
})

