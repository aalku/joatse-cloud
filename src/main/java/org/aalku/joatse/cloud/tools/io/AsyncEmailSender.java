package org.aalku.joatse.cloud.tools.io;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class AsyncEmailSender implements InitializingBean, DisposableBean {

	private Boolean enabled;
	
	@Value("${spring.mail.username:}")
	private String emailFrom;
	
	public class Task {
		private CompletableFuture<Void> cf = new CompletableFuture<>();
		private SimpleMailMessage simpleMessage;
		public Task(SimpleMailMessage message) {
			this.simpleMessage = message;
		}
		void run() {
			try {
				if (simpleMessage.getFrom() == null) {
					simpleMessage.setFrom(emailFrom);
				}
				emailSender.send(simpleMessage);
				cf.complete(null);
			} catch (Exception e) {
				fail(e);
			}
		}
		public void fail(Exception e) {
			log.warn("Failed sending mail", e);
			cf.completeExceptionally(e);
		}
	}

	@Autowired(required = false)
    private JavaMailSenderImpl emailSender;

	private Logger log = LoggerFactory.getLogger(AsyncEmailSender.class);
	
	private Thread thread;
	private AtomicBoolean closed = new AtomicBoolean(false);
	
	private BlockingQueue<Task> queue = new BlockingArrayQueue<Task>();
	
	protected void sendMailFromQueue() throws InterruptedException {
		Task task = queue.take();
		try {
			if (closed.get()) {
				task.fail(new IllegalStateException("closed"));
			} else {
				task.run();
			}
		} catch (Exception e) {
			log.error("Mail sender error", e);
		}
	}

	@Override
	public void destroy() throws Exception {
		closed.set(true);
		queue.clear();		
		thread.interrupt();
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		enabled = emailSender != null;
		if (enabled) {
			emailSender.testConnection();
			thread = new Thread("mailSender") {
				@Override
				public void run() {
					try {
						while (!closed.get()) {
							sendMailFromQueue();
						}
					} catch (Exception e) {
						if (!closed.get()) {
							log.error("Mail sender error", e);
						}
					}
				}
			};
			thread.start();
		}
	}

	public CompletableFuture<Void> send(SimpleMailMessage message) {
		Task task = new Task(message);
		if (!enabled) {
			task.fail(new IllegalStateException("Mail sending is not enabled"));
		} else if (closed.get()) {
			task.fail(new IllegalStateException("closed"));
		} else {
			queue.add(task);
		}
		return task.cf;
	}
	
	public boolean isEnabled() {
		return enabled;
	}
}
