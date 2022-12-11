import Header from '/header.js'

const App = {
	el: "#app",
	components: {
		'app-header': Header
	},
	data: {
		user: {},
		fields: [{ key: 'UUID', label: 'UUID' }, 'login', 'role', { key: 'actions', label: 'Actions' }],
		editingUser: null,
		editUserTitle: null,
		passwordMandatory: null,
		validated: false,
		roles: { "USER": "User", "ADMIN": "Admin" }
	},
	computed: {
		loginState() {
			return !!this.editingUser?.login?.match(/^(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|"(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21\x23-\x5b\x5d-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])*")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\x01-\x08\x0b\x0c\x0e-\x1f\x21-\x5a\x53-\x7f]|\\[\x01-\x09\x0b\x0c\x0e-\x7f])+)\])$/);
		},
		passwordState() {
			if (!this.passwordMandatory && !this.editingUser.password) {
				return true;
			}
			return !!this.editingUser?.password?.match(/^\S.{6,}\S$/);
		},
		passwordConfirmState() {
			return this.editingUser?.password == this.editingUser?.password2;
		},
		formValid() {
			return this.loginState && this.passwordState && this.passwordConfirmState;
		},
		pathname() {
			return new URL(window.location.href).pathname;
		}
	},
	methods: {
		async userProvider(ctx) {
			try {
				return await fetch('/admin/users/list').then(response => response.json())
					.then((data) => data.users);
			} catch (error) {
				console.error("Error getting users", error);
				return [];
			}
		},
		async editUser(user) {
			this.editingUser = { ...user };
			this.validated = false;
			this.editUserTitle = `Edit user '${this.editingUser?.login}'`;
			this.passwordMandatory = false;
			this.$refs.editUserModal.show();
		},
		async addUser() {
			this.editingUser = { role: "USER" };
			this.validated = false;
			this.editUserTitle = 'Create user';
			this.passwordMandatory = true;
			this.$refs.editUserModal.show();
		},
		editUserModalOk(bvModalEvent) {
			// Prevent modal from closing
			bvModalEvent.preventDefault();
			// Trigger submit handler
			this.handleSubmit();
		},
		editUserModalReset() {
			this.editingUser = null;
			this.editUserTitle = null;
			this.validation = null;
		},
		async handleSubmit() {
			await this.saveUser(this.editingUser);
			this.$nextTick(() => {
				this.$refs.editUserModal.hide();
				this.editUserModalReset();
			})
		},
		async saveUser(user) {
			console.log("Saving data...", user);
			let u = { login: user.login, role: user.role };
			if (user.password) {
				u.password = user.password;
			}
			let res = null;
			let body = null;
			let error = null;
			try {
				if (user.UUID) {
					u.UUID = user.UUID;
					res = await fetch('/admin/users', {
						method: 'PUT',
						headers: {
							'Content-type': 'application/json; charset=UTF-8',
						},
						body: JSON.stringify(u)
					});
				} else {
					res = await fetch('/admin/users', {
						method: 'POST',
						headers: {
							'Content-type': 'application/json; charset=UTF-8',
						},
						body: JSON.stringify(u)
					});
				}
				body = await res.json();
			} catch (e) {
				error = e;
			}
			console.log("Result", res, body, error);
			if (res.ok) {
				this.$refs.users.refresh();
			} else {
				if (error) {
					console.error(error);
				}
				console.error(body);
				// TODO
			}
		},
		async deleteUser(user) {
			this.$bvModal.msgBoxConfirm('Please confirm that you want to delete the user: ' + user.login, {
				title: 'Please Confirm',
				size: 'sm',
				buttonSize: 'sm',
				okVariant: 'danger',
				okTitle: 'Delete',
				cancelTitle: 'Cancel',
				footerClass: 'p-2',
				hideHeaderClose: false,
				centered: true
			})
				.then(async value => {
					console.info("delete?", value)
					if (value) {
						await fetch('/admin/users/' + user.UUID, { method: 'DELETE' });
						this.$refs.users.refresh();
					}
				})
				.catch(err => {
					console.error("delete?", err);
					this.$refs.users.refresh();
				})
		},
	},
	async created() {
		let loadData = async () => {
		}
		await loadData();
		setInterval(() => loadData(), 5000);
	}
};


window.addEventListener('load', () => {
  new Vue(App)
})

