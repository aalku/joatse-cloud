const template = `
	<b-navbar toggleable="lg" type="dark" variant="dark">
		<b-navbar-brand href="/">Joatse Cloud</b-navbar-brand>
		<b-navbar-toggle target="nav-collapse"></b-navbar-toggle>
		<b-collapse id="nav-collapse" is-nav>
			<b-navbar-nav class="ml-auto">
				<b-nav-item href="/admin/users" right v-if="pathname != '/admin/users' && user.isAdmin">Admin users</b-nav-item>
				<b-nav-item-dropdown right>
					<template #button-content><em>{{user.nameToAddress}}</em></template>
					<b-dropdown-item href="/logout">Sign Out</b-dropdown-item>
				</b-nav-item-dropdown>
			</b-navbar-nav>
		</b-collapse>
	</b-navbar>
`

export default {
	template,
	// props: ['user'],
	data: () => ({
		user:{}
	}),
	methods: {
	},			
	computed: {
		pathname() {
			return new URL(window.location.href).pathname;
		}
	},
	async mounted() {
		let loadUser = async ()=> {
			await fetch('/user').then(response => response.json())
    			.then((data) => this.user = data);
		}
    	await loadUser();
		setInterval(()=>loadUser(), 30000);
	}
}