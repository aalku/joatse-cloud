# Joatse Project

**J**ava **O**pen **A**ccessible **T**unneling **S**oftware **E**lement

## What's Joatse

Joatse project intends to create an open source tunneling solution to share private resources with yourself or trusted people despite firewalls blocking incoming connections.

It's designed for sysadmins and software developers to access resources they already have access to but quickly and as easy as possible. Also they must be able to give access to other selected people.

Right now you can share access to any TCP port that is accessible to you but in the future we would like to be able to share UDP ports, files, folders and we intentd to have specific support for http(s).

With the current version you can share http and https they runs over TCP but you might have all kinds of problems with https certificates as the cloud hostname will not be the same as the one on the SSL certificate. In order to solve it we intend to support https behaving like a proxy, in a future version.

## How to use it

The first thing you need to know is that Joatse is a software solution and not a service, so don't expect a simple guide to start using it in 5 minutes.

You need to install and run a server that is accessible from all the involved computers. Of course it's part of this project to make it easy for you so we intend to create guides. Beside that as this is open source maybe someday there will be service providers running their own servers and you will be able to just use them.

Once you got a server running you just have to run the client software telling what cloud it should use and what do you want to share. It will give you a http(s) link and you have to open it from the computer that needs to have access to the shared resource. You need to use the same computer so your public IP address is registered in the server and it can give access only to you (and anyone sharing your IP address like coworkers or roommates).

You don't need to use passwords with the client software so anyone else sharing the same computer.

## Architecture

The code is written in Java 

Joatse uses it's own "cloud" server software as web server, tunnel broker and sharing open ports. It needs Java 11+.

[Joatse client software](https://github.com/aalku/joatse-target) connects to that server with web sockets, offering local resources to share. It needs Java 8+ so you can run it where Java 11+ is not installed.

Once the user confirms the tunnel creation the server will open a TCP port and tunnel any authorized incoming connection through the very same web socket to the client software and it will connect it to a new TCP connection to the target.

## Project state

It just started working so it isn't beautiful, it might crash and the functionality is limited, but it really works.

You can find the client software [here](https://github.com/aalku/joatse-target).

There's a lot of work ahead. We need to create use guides a better web interface and a lot of improvements.
We need specific support for https and better support (maybe safer) for http.

The server has a prototype of user system but it currently generates a random admin password that's not even stored anywhere so it's random again on every restart and you have to search for it in the server logs. But if you register your server in Google APIs then you can log in with your Google account too.

We want to have a developed local user system and support other providers of delegated authentication like Facebook or GitHub.

We are working on providing a free demo server you will be able to use, but without any warranty.

The speed of development will depend on how motivated I am and what feedback or help I receive so I wouldn't expect much as I don't think it's likely that many people even finds this repository but I will try to advance in the project anyway.

Please open an issue if you want any help installing your own server instance, if you want to help, if you want to request a feature or if you find a bug.