package org.aalku.joatse.cloud.tools.io;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Component;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Component
public class AsyncEmailSender implements InitializingBean, DisposableBean {

	private Boolean enabled;
	
	@Value("${spring.mail.username:}")
	private String emailFrom;
	
	public static Pattern PATTERN_EMAIL = Pattern.compile("^(?=.{1,64}@)[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*@[^-][A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*(\\.[A-Za-z]{2,})$");
	
	public class Task {
		private CompletableFuture<Void> cf = new CompletableFuture<>();
		private SimpleMailMessage simpleMessage = null;
		private MimeMessagePreparator mimeMessagePreparator = null;
		public Task(MimeMessagePreparator mimeMessagePreparator) {
			this.mimeMessagePreparator = mimeMessagePreparator;
		}
		public Task(SimpleMailMessage message) {
			this.simpleMessage = message;
		}
		void run() {
			try {
				if (simpleMessage != null) {
					if (simpleMessage.getFrom() == null) {
						simpleMessage.setFrom(emailFrom);
					}
					emailSender.send(simpleMessage);
				} else if (mimeMessagePreparator != null) {
					emailSender.send(new MimeMessagePreparator() {
						@Override
						public void prepare(MimeMessage mimeMessage) throws Exception {
							mimeMessagePreparator.prepare(mimeMessage);
							if (mimeMessage.getFrom() == null) {
								mimeMessage.setFrom(emailFrom);
							}
						}
					});
				}
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
		return send(new Task(message));
	}

	public CompletableFuture<Void> sendHtml(SimpleMailMessage message) {
	    MimeMessagePreparator preparator = new MimeMessagePreparator() {
	        public void prepare(MimeMessage mimeMessage) throws jakarta.mail.MessagingException {
				Arrays.asList(Optional.ofNullable(message.getTo()).orElse(new String[0]))
						.forEach(to -> IOTools.runFailable(
								() -> mimeMessage.addRecipient(Message.RecipientType.TO, new InternetAddress(to))));
				Arrays.asList(Optional.ofNullable(message.getCc()).orElse(new String[0]))
						.forEach(to -> IOTools.runFailable(
								() -> mimeMessage.addRecipient(Message.RecipientType.CC, new InternetAddress(to))));
				Arrays.asList(Optional.ofNullable(message.getBcc()).orElse(new String[0]))
						.forEach(to -> IOTools.runFailable(
								() -> mimeMessage.addRecipient(Message.RecipientType.BCC, new InternetAddress(to))));
				Optional.ofNullable(message.getSubject())
						.ifPresent(s -> IOTools.runFailable(() -> mimeMessage.setSubject(s)));
				Optional.ofNullable(message.getReplyTo()).ifPresent(rto -> IOTools
						.runFailable(() -> mimeMessage.setReplyTo(new Address[] { new InternetAddress(rto) })));
				Optional.ofNullable(message.getFrom())
						.ifPresent(from -> IOTools.runFailable(() -> mimeMessage.setFrom(new InternetAddress(from))));
				Optional.ofNullable(message.getText())
						.ifPresent(text -> IOTools.runFailable(() -> mimeMessage.setText(text, "utf8", "html")));
	        }
	    };
		return send(new Task(preparator));
	}

	private CompletableFuture<Void> send(Task task) {
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
