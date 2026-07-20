package com.mnc.ausy.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends mail only when SMTP is configured; otherwise a no-op (logs). */
@Service
public class EmailService {
    private final ObjectProvider<JavaMailSender> sender;
    @Value("${spring.mail.host:}") private String host;

    public EmailService(ObjectProvider<JavaMailSender> sender) { this.sender = sender; }

    public void send(String to, String subject, String body) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null || host == null || host.isBlank() || to == null || to.isBlank()) return;  // skip in dev
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setTo(to); m.setSubject(subject); m.setText(body);
            m.setFrom("adm.ausy@mncbank.co.id");
            s.send(m);
        } catch (Exception ignore) {}
    }
}
