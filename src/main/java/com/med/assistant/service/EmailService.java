package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@mediassist.com}")
    private String fromEmail;

    @Value("${app.base-url:https://mediassist-1hdl.onrender.com}")
    private String baseUrl;

    public EmailService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
        if (mailSender == null) {
            logger.warn("JavaMailSender is not configured. Emails will be logged to console instead of sent via SMTP.");
        }
    }

    /**
     * Send welcome email with login credentials to a newly approved hospital admin.
     */
    public void sendCredentials(String toEmail, String hospitalName, String fullName, String tempPassword) {
        try {
            String subject = "Welcome to MediAssist — Your Hospital Portal Credentials";
            String body = """
                <html>
                <body style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; color: #111827;">
                    <div style="background: linear-gradient(135deg, #0066FF, #0052CC); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: #ffffff; margin: 0; font-size: 24px;">MediAssist</h1>
                        <p style="color: #bfdbfe; margin: 5px 0 0;">AI-Powered Hospital Operations Platform</p>
                    </div>
                    <div style="background: #ffffff; padding: 30px; border: 1px solid #E5E7EB; border-top: none; border-radius: 0 0 12px 12px;">
                        <h2 style="color: #111827; margin-top: 0;">Welcome, %s!</h2>
                        <p>Your hospital <strong>%s</strong> has been approved on the MediAssist platform. You can now manage your hospital operations using the portal.</p>
                        
                        <div style="background: #F9FAFB; border: 1px solid #E5E7EB; border-radius: 8px; padding: 20px; margin: 20px 0;">
                            <h3 style="margin-top: 0; color: #0066FF;">Your Login Credentials</h3>
                            <table style="width: 100%%; border-collapse: collapse;">
                                <tr>
                                    <td style="padding: 8px 0; font-weight: 600; color: #4B5563;">Portal URL:</td>
                                    <td style="padding: 8px 0;"><a href="%s/login.html" style="color: #0066FF;">%s/login.html</a></td>
                                </tr>
                                <tr>
                                    <td style="padding: 8px 0; font-weight: 600; color: #4B5563;">Email:</td>
                                    <td style="padding: 8px 0;"><code style="background: #EBF2FF; padding: 2px 8px; border-radius: 4px;">%s</code></td>
                                </tr>
                                <tr>
                                    <td style="padding: 8px 0; font-weight: 600; color: #4B5563;">Temporary Password:</td>
                                    <td style="padding: 8px 0;"><code style="background: #FEF3C7; padding: 2px 8px; border-radius: 4px;">%s</code></td>
                                </tr>
                            </table>
                        </div>
                        
                        <p style="color: #DC2626; font-weight: 600;">⚠ Please change your password after first login.</p>
                        
                        <h3>What you can do:</h3>
                        <ul style="color: #4B5563; line-height: 1.8;">
                            <li>Add and manage your hospital's doctors</li>
                            <li>Set consultation fees and daily token limits</li>
                            <li>Toggle doctor availability</li>
                            <li>View today's appointments and patient queue</li>
                            <li>Use the reception QR check-in system</li>
                        </ul>
                        
                        <a href="%s/login.html" style="display: inline-block; background: #0066FF; color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-weight: 600; margin-top: 10px;">Login to Your Portal →</a>
                        
                        <hr style="border: none; border-top: 1px solid #E5E7EB; margin: 25px 0;">
                        <p style="font-size: 12px; color: #9CA3AF;">This is an automated email from MediAssist. If you did not apply, please ignore this email.</p>
                    </div>
                </body>
                </html>
                """.formatted(fullName, hospitalName, baseUrl, baseUrl, toEmail, tempPassword, baseUrl);

            if (mailSender == null) {
                logger.info("JavaMailSender not configured. Credentials for {} (Hospital: {}): Email={}, TempPass={}",
                        fullName, hospitalName, toEmail, tempPassword);
                return;
            }

            sendHtmlEmail(toEmail, subject, body);
            logger.info("Credentials email sent to {} for hospital '{}'", toEmail, hospitalName);
        } catch (Exception e) {
            logger.error("Failed to send credentials email to {}: {}", toEmail, e.getMessage(), e);
        }
    }

    /**
     * Send rejection email to a hospital applicant.
     */
    public void sendRejection(String toEmail, String hospitalName, String contactName, String reason) {
        try {
            String subject = "MediAssist Application Update — " + hospitalName;
            String body = """
                <html>
                <body style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; color: #111827;">
                    <div style="background: #111827; padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: #ffffff; margin: 0; font-size: 24px;">MediAssist</h1>
                    </div>
                    <div style="background: #ffffff; padding: 30px; border: 1px solid #E5E7EB; border-top: none; border-radius: 0 0 12px 12px;">
                        <h2 style="color: #111827; margin-top: 0;">Dear %s,</h2>
                        <p>Thank you for your interest in joining the MediAssist platform. After careful review, we are unable to approve the application for <strong>%s</strong> at this time.</p>
                        
                        <div style="background: #FEF2F2; border: 1px solid #FECACA; border-radius: 8px; padding: 15px; margin: 20px 0;">
                            <strong style="color: #DC2626;">Reason:</strong>
                            <p style="margin: 5px 0 0; color: #7F1D1D;">%s</p>
                        </div>
                        
                        <p>You are welcome to reapply after addressing the above concerns. If you have questions, please contact our support team.</p>
                        
                        <hr style="border: none; border-top: 1px solid #E5E7EB; margin: 25px 0;">
                        <p style="font-size: 12px; color: #9CA3AF;">MediAssist — AI-Powered Hospital Operations Platform</p>
                    </div>
                </body>
                </html>
                """.formatted(contactName, hospitalName, reason != null ? reason : "Application does not meet our current requirements.");

            if (mailSender == null) {
                logger.info("JavaMailSender not configured. Rejection for {} (Hospital: {}): Reason={}",
                        contactName, hospitalName, reason);
                return;
            }

            sendHtmlEmail(toEmail, subject, body);
            logger.info("Rejection email sent to {} for hospital '{}'", toEmail, hospitalName);
        } catch (Exception e) {
            logger.error("Failed to send rejection email to {}: {}", toEmail, e.getMessage(), e);
        }
    }

    private void sendHtmlEmail(String to, String subject, String htmlBody) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(message);
    }
}
