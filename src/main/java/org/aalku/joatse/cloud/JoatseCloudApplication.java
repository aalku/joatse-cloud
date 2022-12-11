package org.aalku.joatse.cloud;

import java.util.List;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.tools.io.AsyncEmailSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableJpaRepositories
@EnableTransactionManagement
@SpringBootApplication
public class JoatseCloudApplication {

	@Value("${cloud.alert.emails:}")
	private List<String> alertEmails;

	@Autowired
	private AsyncEmailSender emailSender;

	public static void main(String[] args) {
		SpringApplication.run(JoatseCloudApplication.class, args);
	}

	@EventListener(ApplicationStartedEvent.class)
	public void doSomethingAfterStartup(ApplicationStartedEvent event) {
		if (emailSender.isEnabled()) {
			for (String dest : alertEmails.stream().filter(e -> !e.isEmpty()).collect(Collectors.toList())) {
				SimpleMailMessage message = new SimpleMailMessage();
				message.setTo(dest);
				message.setSubject("Joatse Cloud just started");
				message.setText("Hi! Joatse Cloud just started. Bye!");
				emailSender.send(message);
			}
		}
	}

}
